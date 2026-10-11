package cn.vv.test.mysqlmock.schema;

/** 列定义（不可变）。 */
public final class ColumnDefinition {

    public enum GeneratedKind { NONE, STORED, VIRTUAL }

    private final String name;
    private final MysqlType type;
    private final int precision;
    private final int scale;
    private final boolean unsigned;
    private final boolean notNull;
    private final boolean autoIncrement;
    private final boolean primaryKey;
    private final boolean unique;
    private final boolean hasDefault;
    private final Object defaultValue;
    private final boolean defaultCurrentTimestamp;
    private final boolean onUpdateCurrentTimestamp;
    private final GeneratedKind generatedKind;
    private final String generatedExpr;
    private final String collation;
    private final String comment;

    private ColumnDefinition(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.precision = builder.precision;
        this.scale = builder.scale;
        this.unsigned = builder.unsigned;
        this.notNull = builder.notNull;
        this.autoIncrement = builder.autoIncrement;
        this.primaryKey = builder.primaryKey;
        this.unique = builder.unique;
        this.hasDefault = builder.hasDefault;
        this.defaultValue = builder.defaultValue;
        this.defaultCurrentTimestamp = builder.defaultCurrentTimestamp;
        this.onUpdateCurrentTimestamp = builder.onUpdateCurrentTimestamp;
        this.generatedKind = builder.generatedKind;
        this.generatedExpr = builder.generatedExpr;
        this.collation = builder.collation;
        this.comment = builder.comment;
    }

    public String name() {
        return name;
    }

    public MysqlType type() {
        return type;
    }

    public int precision() {
        return precision;
    }

    public int scale() {
        return scale;
    }

    public boolean unsigned() {
        return unsigned;
    }

    public boolean notNull() {
        return notNull;
    }

    public boolean autoIncrement() {
        return autoIncrement;
    }

    public boolean primaryKey() {
        return primaryKey;
    }

    public boolean unique() {
        return unique;
    }

    public boolean hasDefault() {
        return hasDefault;
    }

    public Object defaultValue() {
        return defaultValue;
    }

    public boolean defaultCurrentTimestamp() {
        return defaultCurrentTimestamp;
    }

    public boolean onUpdateCurrentTimestamp() {
        return onUpdateCurrentTimestamp;
    }

    public GeneratedKind generatedKind() {
        return generatedKind;
    }

    public String generatedExpr() {
        return generatedExpr;
    }

    public boolean generated() {
        return generatedKind != GeneratedKind.NONE;
    }

    public String collation() {
        return collation;
    }

    public String comment() {
        return comment;
    }

    public static Builder builder(String name, MysqlType type) {
        return new Builder(name, type);
    }

    /** 构造器。 */
    public static final class Builder {

        private final String name;
        private final MysqlType type;
        private int precision = -1;
        private int scale = -1;
        private boolean unsigned;
        private boolean notNull;
        private boolean autoIncrement;
        private boolean primaryKey;
        private boolean unique;
        private boolean hasDefault;
        private Object defaultValue;
        private boolean defaultCurrentTimestamp;
        private boolean onUpdateCurrentTimestamp;
        private GeneratedKind generatedKind = GeneratedKind.NONE;
        private String generatedExpr;
        private String collation;
        private String comment;

        private Builder(String name, MysqlType type) {
            this.name = name.toLowerCase();
            this.type = type;
        }

        public Builder precision(int precision) {
            this.precision = precision;
            return this;
        }

        public Builder scale(int scale) {
            this.scale = scale;
            return this;
        }

        public Builder unsigned(boolean unsigned) {
            this.unsigned = unsigned;
            return this;
        }

        public Builder notNull(boolean notNull) {
            this.notNull = notNull;
            return this;
        }

        public Builder autoIncrement(boolean autoIncrement) {
            this.autoIncrement = autoIncrement;
            return this;
        }

        public Builder primaryKey(boolean primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public Builder unique(boolean unique) {
            this.unique = unique;
            return this;
        }

        public Builder defaultValue(boolean hasDefault, Object defaultValue) {
            this.hasDefault = hasDefault;
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder defaultCurrentTimestamp(boolean flag) {
            this.defaultCurrentTimestamp = flag;
            return this;
        }

        public Builder onUpdateCurrentTimestamp(boolean flag) {
            this.onUpdateCurrentTimestamp = flag;
            return this;
        }

        public Builder generated(GeneratedKind kind, String expr) {
            this.generatedKind = kind;
            this.generatedExpr = expr;
            return this;
        }

        public Builder collation(String collation) {
            this.collation = collation;
            return this;
        }

        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        public ColumnDefinition build() {
            return new ColumnDefinition(this);
        }
    }
}
