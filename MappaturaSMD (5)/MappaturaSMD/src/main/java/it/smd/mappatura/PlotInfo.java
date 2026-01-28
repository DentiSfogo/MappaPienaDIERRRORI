package it.smd.mappatura;

public class PlotInfo {
    public final String plotId;        // "3399" (SENZA #)
    public final int coordX;
    public final int coordZ;
    public final String dimension;
    public final String proprietario;  // può essere null
    public final String ultimoAccessoIso; // ISO o raw, può essere null
    public final long requestId; // id richiesta associata

    public PlotInfo(String plotId, int coordX, int coordZ, String dimension, String proprietario, String ultimoAccessoIso) {
        this(plotId, coordX, coordZ, dimension, proprietario, ultimoAccessoIso, 0L);
    }

    public PlotInfo(String plotId, int coordX, int coordZ, String dimension, String proprietario, String ultimoAccessoIso, long requestId) {
        this.plotId = plotId;
        this.coordX = coordX;
        this.coordZ = coordZ;
        this.dimension = dimension;
        this.proprietario = proprietario;
        this.ultimoAccessoIso = ultimoAccessoIso;
        this.requestId = requestId;
    }
}
