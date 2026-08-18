package com.otool.eprint.protocol;

/** Job-level Dothan bitmap options corresponding to BitmapPackage.v(Bundle). */
public final class DothanJobOptions {
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    public final int copies;
    public final int leftDots;
    public final int rightDots;
    public final int topDots;
    public final int bottomDots;
    public final int alignment;
    public final int direction;
    public final boolean flipHorizontal;
    public final boolean antiColor;

    public DothanJobOptions(int copies, int leftDots, int rightDots, int topDots,
                            int bottomDots, int alignment, int direction,
                            boolean flipHorizontal, boolean antiColor) {
        this.copies = Math.max(1, copies);
        this.leftDots = Math.max(0, leftDots);
        this.rightDots = Math.max(0, rightDots);
        this.topDots = Math.max(0, topDots);
        this.bottomDots = Math.max(0, bottomDots);
        this.alignment = alignment;
        this.direction = direction;
        this.flipHorizontal = flipHorizontal;
        this.antiColor = antiColor;
    }

    public static DothanJobOptions defaults() {
        return new DothanJobOptions(1, 0, 0, 0, 0,
                ALIGN_LEFT, 0, false, false);
    }
}
