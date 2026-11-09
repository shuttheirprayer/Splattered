package com.misanthropy.splattered;

public enum BloodType {
    RED(0x700A0A, 0x461E16, 0x8E2E2A, 1.0F, 1.0F, false),
    ROTTEN(0x4E180C, 0x2E1A10, 0x5E3A2A, 0.7F, 1.0F, false),
    ICHOR(0x9AA022, 0x55561A, 0x3B3A18, 0.9F, 1.0F, false),
    ENDER(0x7A2EA8, 0x2E1240, 0x3A1650, 0.9F, 1.0F, true),
    SLIME(0x5DB040, 0x2F5A26, 0x4E9636, 1.3F, 0.82F, false),
    MAGMA(0xF07A18, 0x241A16, 0x3A1A10, 1.0F, 1.0F, true),
    SCULK(0x10808C, 0x0A3036, 0x0C2A34, 1.0F, 1.0F, true),
    INK(0x1A1A22, 0x111116, 0x6A4A5A, 1.2F, 1.0F, false),
    GLOW_INK(0x30D0C0, 0x0E4A46, 0x1E4A50, 1.2F, 1.0F, true),
    SAP(0x4F7A2A, 0x2A3A18, 0x2E4A1E, 0.8F, 1.0F, false),
    ECTO(0xA8C4D8, 0x6A7C8A, 0x8AA6BA, 0.6F, 0.66F, false),
    BONE(0xE6DFC8, 0xB8B09A, 0xD8D0B8, 0.5F, 1.0F, false),
    NONE(0, 0, 0, 0.0F, 0.0F, false);

    public final int wet;
    public final int dried;
    public final int flesh;
    public final float amount;
    public final float alpha;
    public final boolean glow;

    BloodType(int wet, int dried, int flesh, float amount, float alpha, boolean glow) {
        this.wet = wet;
        this.dried = dried;
        this.flesh = flesh;
        this.amount = amount;
        this.alpha = alpha;
        this.glow = glow;
    }

    public boolean bleeds() {
        return this != NONE;
    }

    public boolean stains() {
        return this != NONE && this != BONE;
    }

    public static final BloodType[] ALL = values();
}
