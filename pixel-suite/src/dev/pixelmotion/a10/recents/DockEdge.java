package dev.pixelmotion.a10.recents;

enum DockEdge {
    RIGHT(1, 0, false), BOTTOM(0, 1, true), LEFT(-1, 0, true), TOP(0, -1, false);
    final int outwardX, outwardY;
    final boolean reversed;

    DockEdge(int outwardX, int outwardY, boolean reversed) {
        this.outwardX = outwardX;
        this.outwardY = outwardY;
        this.reversed = reversed;
    }

    static DockEdge forOverview(int touchRotation, int bufferRotation) {
        if ((touchRotation & 1) == 0) return BOTTOM;
        switch ((touchRotation - bufferRotation) & 3) {
            case 0: return RIGHT;
            case 1: return BOTTOM;
            case 2: return LEFT;
            default: return TOP;
        }
    }

    boolean vertical() { return outwardX != 0; }

    int inset(int left, int top, int right, int bottom) {
        switch (this) {
            case RIGHT: return right;
            case LEFT: return left;
            case TOP: return top;
            default: return bottom;
        }
    }

    int slotCenter(int index, int count, int slots, int span) {
        float cell = span / (float) slots;
        int position = reversed ? count - 1 - index : index;
        return Math.round((span - cell * count) / 2f + (position + .5f) * cell);
    }
}
