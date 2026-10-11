package cn.vv.test.mysqlmock.schema;

import cn.vv.test.mysqlmock.api.MysqlMockException;
import org.apache.shardingsphere.sql.parser.api.CacheOption;
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL CREATE TABLE 解析：ShardingSphere Parser 5.3.2 做语法门禁（D-03），
 * 列级细节（生成列表达式、默认值、COLLATE、ON UPDATE）从受控原文提取为自有表模型。
 * 拒绝口径：外键 / CHECK / VIRTUAL 生成列 / 清单外 collation 与 charset / 清单外类型（D-13/D-16）。
 */
public final class MySqlDdlParser {

    /** D-16 支持清单。 */
    private static final Set<String> SUPPORTED_COLLATIONS =
            new HashSet<>(Arrays.asList("utf8mb4_general_ci", "utf8mb4_unicode_ci"));

    private static final SQLParserEngine GATEKEEPER = new SQLParserEngine("MySQL", new CacheOption(0, 0));

    private static final Pattern CREATE_PREFIX =
            Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?");
    private static final Pattern TYPE_TOKEN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?");
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(?i)\\b(NOT\\s+NULL|AUTO_INCREMENT|PRIMARY\\s+KEY|UNIQUE\\s+KEY|UNIQUE|UNSIGNED|ZEROFILL|DEFAULT|NULL"
                    + "|ON\\s+UPDATE\\s+CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\d+\\s*\\))?"
                    + "|COLLATE|CHARACTER\\s+SET|CHARSET"
                    + "|COMMENT|GENERATED\\s+ALWAYS\\s+AS|AS|REFERENCES|CHECK)\\b");
    private static final Pattern WORD_AFTER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*");

    public boolean isIfNotExists(String sql) {
        Matcher matcher = CREATE_PREFIX.matcher(sql.trim());
        return matcher.find() && matcher.group(1) != null;
    }

    /** 解析单条 CREATE TABLE 语句。 */
    public TableDefinition parse(String sql) {
        String trimmed = sql.trim();
        try {
            GATEKEEPER.parse(trimmed, false);
        } catch (RuntimeException e) {
            throw MysqlMockException.parseError(trimmed.substring(0, Math.min(60, trimmed.length()))
                    + "（ShardingSphere 解析失败: " + e.getMessage() + "）");
        }
        Matcher matcher = CREATE_PREFIX.matcher(trimmed);
        if (!matcher.find()) {
            throw MysqlMockException.unsupported("仅支持 CREATE TABLE，收到: "
                    + trimmed.substring(0, Math.min(40, trimmed.length())));
        }
        int pos = matcher.end();
        QualifiedName name = readQualifiedName(trimmed, pos);
        int open = trimmed.indexOf('(', name.nextPos);
        if (open < 0) {
            throw MysqlMockException.parseError("CREATE TABLE 缺少列定义括号: " + name.table);
        }
        int close = SqlTextUtils.findMatchingParen(trimmed, open);
        if (close < 0) {
            throw MysqlMockException.parseError("CREATE TABLE 括号不匹配: " + name.table);
        }
        String body = trimmed.substring(open + 1, close);
        String tail = trimmed.substring(close + 1);
        return buildTable(name, body, tail);
    }

    private TableDefinition buildTable(QualifiedName name, String body, String tail) {
        TableOptions options = parseTailOptions(tail, name.table);
        List<ColumnDefinition> columns = new ArrayList<>();
        List<IndexDefinition> indexes = new ArrayList<>();
        for (String item : splitItems(body)) {
            String trimmedItem = item.trim();
            if (trimmedItem.isEmpty()) {
                continue;
            }
            String upper = trimmedItem.toUpperCase();
            if (upper.startsWith("PRIMARY")) {
                indexes.add(parseIndex(trimmedItem, IndexDefinition.Kind.PRIMARY, name.table));
            } else if (upper.startsWith("CONSTRAINT")) {
                parseConstraintItem(trimmedItem, name.table);
                indexes.add(parseIndex(stripConstraintPrefix(trimmedItem), IndexDefinition.Kind.UNIQUE, name.table));
            } else if (upper.startsWith("FOREIGN") || upper.startsWith("CHECK")
                    || upper.startsWith("FULLTEXT") || upper.startsWith("SPATIAL")) {
                rejectDefinition(trimmedItem, name.table);
            } else if (upper.startsWith("UNIQUE")) {
                indexes.add(parseIndex(trimmedItem, IndexDefinition.Kind.UNIQUE, name.table));
            } else if (upper.startsWith("KEY") || upper.startsWith("INDEX")) {
                indexes.add(parseIndex(trimmedItem, IndexDefinition.Kind.NORMAL, name.table));
            } else {
                columns.add(parseColumn(trimmedItem, options.collation, name.table));
            }
        }
        synthesizeInlineIndexes(columns, indexes);
        return new TableDefinition(name.database, name.table, columns, indexes,
                options.engine, options.charset, options.collation, options.comment);
    }

    private void rejectDefinition(String item, String table) {
        throw MysqlMockException.unsupported("DDL 定义不受支持（表 " + table + "）: "
                + item.substring(0, Math.min(60, item.length())));
    }

    private void parseConstraintItem(String item, String table) {
        String upper = item.toUpperCase();
        if (upper.contains("FOREIGN") || upper.contains("REFERENCES")) {
            throw MysqlMockException.unsupported("外键不受支持（表 " + table + "）：现网 0 外键，属 strict 显式拒绝（D-13）");
        }
        if (upper.contains("CHECK")) {
            throw MysqlMockException.unsupported("CHECK 约束不受支持（表 " + table + "，D-13）");
        }
        if (!upper.contains("UNIQUE")) {
            throw MysqlMockException.unsupported("无法识别的约束定义（表 " + table + "）: " + item);
        }
    }

    /** CONSTRAINT `name` UNIQUE KEY (...) → UNIQUE KEY (...)。 */
    private String stripConstraintPrefix(String item) {
        Pattern constraint = Pattern.compile("(?i)^CONSTRAINT\\s+(?:`[^`]+`|[A-Za-z0-9_$]+)\\s*");
        return item.replaceFirst(constraint.pattern(), "");
    }

    private IndexDefinition parseIndex(String item, IndexDefinition.Kind kind, String table) {
        int open = item.indexOf('(');
        int close = SqlTextUtils.findMatchingParen(item, open);
        if (open < 0 || close < 0) {
            throw MysqlMockException.parseError("索引缺少列括号（表 " + table + "）: " + item);
        }
        List<String> columns = new ArrayList<>();
        for (String token : splitItems(item.substring(open + 1, close))) {
            String column = extractIdentifier(token.trim());
            if (column == null) {
                throw MysqlMockException.parseError("索引列无法解析（表 " + table + "）: " + token);
            }
            columns.add(column);
        }
        Matcher nameMatcher = Pattern.compile("(?:`([^`]+)`|([A-Za-z0-9_$]+))").matcher(item.substring(0, open));
        String indexName = kind == IndexDefinition.Kind.PRIMARY ? "PRIMARY" : null;
        while (nameMatcher.find()) {
            String candidate = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
            String upper = candidate.toUpperCase();
            if (!upper.equals("UNIQUE") && !upper.equals("KEY") && !upper.equals("INDEX")
                    && !upper.equals("PRIMARY") && !upper.equals("CONSTRAINT")) {
                indexName = candidate;
                break;
            }
        }
        if (indexName == null) {
            indexName = "idx_" + String.join("_", columns);
        }
        return new IndexDefinition(indexName.toLowerCase(), kind, columns);
    }

    private void synthesizeInlineIndexes(List<ColumnDefinition> columns, List<IndexDefinition> indexes) {
        boolean hasPrimary = false;
        for (IndexDefinition index : indexes) {
            if (index.kind() == IndexDefinition.Kind.PRIMARY) {
                hasPrimary = true;
                break;
            }
        }
        for (ColumnDefinition column : columns) {
            if (column.primaryKey() && !hasPrimary) {
                indexes.add(new IndexDefinition("PRIMARY", IndexDefinition.Kind.PRIMARY,
                        java.util.Collections.singletonList(column.name())));
                hasPrimary = true;
            }
            if (column.unique()) {
                indexes.add(new IndexDefinition("uk_" + column.name(), IndexDefinition.Kind.UNIQUE,
                        java.util.Collections.singletonList(column.name())));
            }
        }
    }

    private ColumnDefinition parseColumn(String item, String tableCollation, String table) {
        int[] pos = {0};
        String columnName = readIdentifier(item, pos);
        if (columnName == null) {
            throw MysqlMockException.parseError("列名无法解析（表 " + table + "）: " + item);
        }
        skipWhitespace(item, pos);
        Matcher typeMatcher = TYPE_TOKEN.matcher(item.substring(pos[0]));
        if (!typeMatcher.find()) {
            throw MysqlMockException.parseError("列类型无法解析（表 " + table + "）: " + item);
        }
        String typeToken = typeMatcher.group();
        pos[0] += typeMatcher.group().length();
        MysqlType type = MysqlType.parse(typeToken);
        if (type == null) {
            throw MysqlMockException.unsupported("列类型不在白名单（表 " + table + " 列 " + columnName + "）: " + typeToken);
        }
        int precision = -1;
        int scale = -1;
        skipWhitespace(item, pos);
        if (pos[0] < item.length() && item.charAt(pos[0]) == '(') {
            int close = SqlTextUtils.findMatchingParen(item, pos[0]);
            String inner = item.substring(pos[0] + 1, close).trim();
            int comma = inner.indexOf(',');
            if (comma >= 0) {
                precision = Integer.parseInt(inner.substring(0, comma).trim());
                scale = Integer.parseInt(inner.substring(comma + 1).trim());
            } else if (!inner.isEmpty()) {
                if (type == MysqlType.DATETIME || type == MysqlType.TIMESTAMP) {
                    // MySQL：DATETIME/TrIMESTAMP 括号是 fsp（小数秒位），非长度
                    scale = Integer.parseInt(inner);
                } else {
                    precision = Integer.parseInt(inner);
                }
            }
            pos[0] = close + 1;
        }
        String rest = item.substring(pos[0]);
        ColumnAttrs attrs = scanColumnAttrs(rest, table, columnName);

        if (attrs.generatedKind == ColumnDefinition.GeneratedKind.VIRTUAL) {
            throw MysqlMockException.unsupported(
                    "VIRTUAL 生成列不受支持（表 " + table + " 列 " + columnName + "，D-13：仅 STORED）");
        }
        String collation = attrs.collation != null ? attrs.collation : tableCollation;
        return ColumnDefinition.builder(columnName, type)
                .precision(precision).scale(scale).unsigned(attrs.unsigned)
                .notNull(attrs.notNull).autoIncrement(attrs.autoIncrement)
                .primaryKey(attrs.primaryKey).unique(attrs.unique)
                .defaultValue(attrs.hasDefault, attrs.defaultValue)
                .defaultCurrentTimestamp(attrs.defaultCurrentTimestamp)
                .onUpdateCurrentTimestamp(attrs.onUpdateCurrentTimestamp)
                .generated(attrs.generatedKind, attrs.generatedExpr)
                .collation(collation).comment(attrs.comment)
                .build();
    }

    private ColumnAttrs scanColumnAttrs(String rest, String table, String column) {
        ColumnAttrs attrs = new ColumnAttrs();
        Matcher attr = ATTR_PATTERN.matcher(rest);
        while (attr.find()) {
            String token = attr.group(1).replaceAll("\\s+", " ").toUpperCase();
            if (token.equals("NOT NULL")) {
                attrs.notNull = true;
            } else if (token.equals("NULL")) {
                attrs.notNull = false;
            } else if (token.equals("AUTO_INCREMENT")) {
                attrs.autoIncrement = true;
            } else if (token.equals("PRIMARY KEY")) {
                attrs.primaryKey = true;
            } else if (token.equals("UNIQUE KEY") || token.equals("UNIQUE")) {
                attrs.unique = true;
            } else if (token.equals("UNSIGNED")) {
                attrs.unsigned = true;
            } else if (token.equals("ZEROFILL")) {
                // 记录但不影响语义
            } else if (token.equals("DEFAULT")) {
                ValueRead value = readDefaultValue(rest, attr.end());
                attrs.hasDefault = true;
                attrs.defaultValue = value.value;
                attrs.defaultCurrentTimestamp = value.currentTimestamp;
                attr.region(value.nextPos, rest.length());
            } else if (token.startsWith("ON UPDATE CURRENT_TIMESTAMP")) {
                attrs.onUpdateCurrentTimestamp = true;
            } else if (token.equals("COLLATE")) {
                attrs.collation = requireSupportedCollation(rest, attr.end(), table, column);
                attr.region(attr.end() + attrs.collation.length(), rest.length());
            } else if (token.equals("CHARACTER SET") || token.equals("CHARSET")) {
                String charset = readWordAt(rest, attr.end());
                if (charset == null || !charset.equalsIgnoreCase("utf8mb4")) {
                    throw MysqlMockException.unsupported("字符集不在支持清单（表 " + table + " 列 " + column + "）: " + charset);
                }
                attr.region(attr.end() + charset.length(), rest.length());
            } else if (token.equals("COMMENT")) {
                Matcher quoted = Pattern.compile("(?is)\\s*'((?:[^']|'')*)'").matcher(rest);
                if (quoted.find(attr.end())) {
                    attrs.comment = SqlTextUtils.unescape(quoted.group(1));
                    attr.region(quoted.end(), rest.length());
                }
            } else if (token.equals("GENERATED ALWAYS AS") || token.equals("AS")) {
                int parenPos = attr.end();
                while (parenPos < rest.length() && Character.isWhitespace(rest.charAt(parenPos))) {
                    parenPos++;
                }
                if (parenPos >= rest.length() || rest.charAt(parenPos) != '(') {
                    throw MysqlMockException.parseError(
                            "生成列缺少表达式括号（表 " + table + " 列 " + column + "）");
                }
                int close = SqlTextUtils.findMatchingParen(rest, parenPos);
                attrs.generatedExpr = rest.substring(parenPos + 1, close).trim();
                int after = close + 1;
                Matcher stored = Pattern.compile("(?is)\\s*(STORED|VIRTUAL)").matcher(rest);
                if (stored.find(after)) {
                    attrs.generatedKind = "STORED".equalsIgnoreCase(stored.group(1))
                            ? ColumnDefinition.GeneratedKind.STORED
                            : ColumnDefinition.GeneratedKind.VIRTUAL;
                    after = stored.end();
                } else {
                    attrs.generatedKind = ColumnDefinition.GeneratedKind.VIRTUAL;
                }
                attr.region(after, rest.length());
            } else if (token.equals("REFERENCES")) {
                throw MysqlMockException.unsupported(
                        "列级外键不受支持（表 " + table + " 列 " + column + "，D-13）");
            } else if (token.equals("CHECK")) {
                throw MysqlMockException.unsupported("CHECK 约束不受支持（表 " + table + " 列 " + column + "，D-13）");
            }
        }
        return attrs;
    }

    private String requireSupportedCollation(String rest, int pos, String table, String column) {
        String collation = readWordAt(rest, pos);
        if (collation == null || !SUPPORTED_COLLATIONS.contains(collation.toLowerCase())) {
            throw MysqlMockException.unsupported("collation 不在支持清单（表 " + table + " 列 " + column
                    + "，D-16 仅 utf8mb4_general_ci / utf8mb4_unicode_ci）: " + collation);
        }
        return collation.toLowerCase();
    }

    private ValueRead readDefaultValue(String rest, int pos) {
        int i = pos;
        while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) {
            i++;
        }
        if (i >= rest.length()) {
            throw MysqlMockException.parseError("DEFAULT 缺少值: " + rest);
        }
        char c = rest.charAt(i);
        if (c == '\'') {
            int end = SqlTextUtils.skipQuoted(rest, i);
            return new ValueRead(SqlTextUtils.unescape(rest.substring(i + 1, end - 1)), end);
        }
        if (c == '`') {
            int end = SqlTextUtils.skipQuoted(rest, i);
            return new ValueRead(rest.substring(i + 1, end - 1), end);
        }
        Matcher number = NUMBER.matcher(rest.substring(i));
        if (number.find()) {
            return new ValueRead(number.group(), i + number.end());
        }
        Matcher word = WORD_AFTER.matcher(rest.substring(i));
        if (word.find()) {
            String value = word.group();
            int next = i + word.group().length();
            if (value.equalsIgnoreCase("NULL")) {
                return new ValueRead(null, next);
            }
            if (value.equalsIgnoreCase("CURRENT_TIMESTAMP")) {
                if (next < rest.length() && rest.charAt(next) == '(') {
                    int close = SqlTextUtils.findMatchingParen(rest, next);
                    next = close + 1;
                }
                return new ValueRead(null, next, true);
            }
            return new ValueRead(value, next);
        }
        throw MysqlMockException.parseError("DEFAULT 值无法解析: " + rest.substring(i));
    }

    private String readWordAt(String rest, int pos) {
        int i = pos;
        while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) {
            i++;
        }
        Matcher word = WORD_AFTER.matcher(rest.substring(i));
        return word.find() ? word.group() : null;
    }

    private TableOptions parseTailOptions(String tail, String table) {
        TableOptions options = new TableOptions();
        Matcher engine = Pattern.compile("(?i)ENGINE\\s*=\\s*([A-Za-z0-9_]+)").matcher(tail);
        if (engine.find()) {
            options.engine = engine.group(1);
        }
        Matcher charset = Pattern.compile("(?i)(?:DEFAULT\\s+)?CHARSET\\s*=\\s*([A-Za-z0-9_]+)").matcher(tail);
        if (charset.find()) {
            options.charset = charset.group(1);
            if (!options.charset.equalsIgnoreCase("utf8mb4")) {
                throw MysqlMockException.unsupported("字符集不在支持清单（表 " + table + "）: " + options.charset);
            }
        }
        Matcher collate = Pattern.compile("(?i)COLLATE\\s*=\\s*([A-Za-z0-9_]+)").matcher(tail);
        if (collate.find()) {
            String collation = collate.group(1);
            if (!SUPPORTED_COLLATIONS.contains(collation.toLowerCase())) {
                throw MysqlMockException.unsupported("collation 不在支持清单（表 " + table + "，D-16）: " + collation);
            }
            options.collation = collation.toLowerCase();
        }
        Matcher comment = Pattern.compile("(?i)COMMENT\\s*=\\s*'((?:[^']|'')*)'").matcher(tail);
        if (comment.find()) {
            options.comment = SqlTextUtils.unescape(comment.group(1));
        }
        return options;
    }

    private List<String> splitItems(String body) {
        List<String> items = new ArrayList<>();
        int start = 0;
        for (Integer comma : SqlTextUtils.topLevelCommas(body)) {
            items.add(body.substring(start, comma));
            start = comma + 1;
        }
        items.add(body.substring(start));
        return items;
    }

    private String extractIdentifier(String token) {
        Matcher matcher = Pattern.compile("^`([^`]+)`|^([A-Za-z0-9_$]+)").matcher(token);
        if (matcher.find()) {
            return matcher.group(1) != null ? matcher.group(1).toLowerCase() : matcher.group(2).toLowerCase();
        }
        return null;
    }

    private QualifiedName readQualifiedName(String sql, int pos) {
        int i = pos;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        List<String> parts = new ArrayList<>();
        while (i < sql.length()) {
            if (sql.charAt(i) == '`') {
                int end = SqlTextUtils.skipQuoted(sql, i);
                parts.add(sql.substring(i + 1, end - 1));
                i = end;
            } else {
                Matcher word = Pattern.compile("[A-Za-z0-9_$]+").matcher(sql);
                if (word.find(i) && word.start() == i) {
                    parts.add(word.group());
                    i = word.end();
                } else {
                    break;
                }
            }
            if (i < sql.length() && sql.charAt(i) == '.') {
                i++;
                while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                continue;
            }
            break;
        }
        if (parts.isEmpty()) {
            throw MysqlMockException.parseError("CREATE TABLE 表名无法解析");
        }
        String database = parts.size() > 1 ? parts.get(parts.size() - 2) : null;
        return new QualifiedName(database, parts.get(parts.size() - 1), i);
    }

    private void skipWhitespace(String text, int[] pos) {
        while (pos[0] < text.length() && Character.isWhitespace(text.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private String readIdentifier(String text, int[] pos) {
        int i = pos[0];
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && text.charAt(i) == '`') {
            int end = SqlTextUtils.skipQuoted(text, i);
            pos[0] = end;
            return text.substring(i + 1, end - 1).toLowerCase();
        }
        Matcher word = Pattern.compile("[A-Za-z0-9_$]+").matcher(text);
        if (word.find(i) && word.start() == i) {
            pos[0] = word.end();
            return word.group().toLowerCase();
        }
        return null;
    }

    private static final class ColumnAttrs {
        boolean notNull;
        boolean autoIncrement;
        boolean primaryKey;
        boolean unique;
        boolean unsigned;
        boolean hasDefault;
        Object defaultValue;
        boolean defaultCurrentTimestamp;
        boolean onUpdateCurrentTimestamp;
        ColumnDefinition.GeneratedKind generatedKind = ColumnDefinition.GeneratedKind.NONE;
        String generatedExpr;
        String collation;
        String comment;
    }

    private static final class ValueRead {
        final Object value;
        final int nextPos;
        final boolean currentTimestamp;

        ValueRead(Object value, int nextPos) {
            this(value, nextPos, false);
        }

        ValueRead(Object value, int nextPos, boolean currentTimestamp) {
            this.value = value;
            this.nextPos = nextPos;
            this.currentTimestamp = currentTimestamp;
        }
    }

    private static final class TableOptions {
        String engine;
        String charset;
        String collation;
        String comment;
    }

    private static final class QualifiedName {
        final String database;
        final String table;
        final int nextPos;

        QualifiedName(String database, String table, int nextPos) {
            this.database = database;
            this.table = table;
            this.nextPos = nextPos;
        }
    }
}
