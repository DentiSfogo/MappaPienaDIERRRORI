package it.smd.mappatura;

public enum TickGate {
    INSTANCE;

    private int counter = 0;

    public void tick() {
        counter++;
        if (counter > 1_000_000) counter = 0;
    }

    public boolean shouldRun(int interval) {
        if (interval <= 1) return true;
        return counter % interval == 0;
    }
}
