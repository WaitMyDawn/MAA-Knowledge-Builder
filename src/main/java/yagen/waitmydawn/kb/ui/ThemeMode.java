package yagen.waitmydawn.kb.ui;

public enum ThemeMode {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
    private final String label;
    ThemeMode(String label) { this.label = label; }
    @Override public String toString() { return label; }
    public static ThemeMode parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) { return SYSTEM; }
    }
}
