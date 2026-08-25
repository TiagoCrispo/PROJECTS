package com.mendozameteo.x10;

/**
 * Coarse operational buckets used only to tune heuristic persistence for X10.
 * They are deliberately not administrative boundaries and never replace SMN areas.
 */
final class MendozaZone {
    enum Kind {
        HIGH_MOUNTAIN("Alta montaña", 3),
        PRECORDILLERA_PIEDEMONTE("Precordillera / piedemonte", 2),
        GRAN_MENDOZA("Gran Mendoza", 2),
        VALLE_DE_UCO("Valle de Uco", 2),
        SOUTH("Sur mendocino", 2),
        EAST("Zona Este", 3),
        OTHER("Mendoza", 2);

        final String label;
        final int minimumPersistenceHours;

        Kind(String label, int minimumPersistenceHours) {
            this.label = label;
            this.minimumPersistenceHours = minimumPersistenceHours;
        }
    }

    private MendozaZone() { }

    static Kind classify(double latitude, double longitude) {
        if (!finite(latitude) || !finite(longitude)) return Kind.OTHER;

        // Cordillera / alta montaña: west of the main populated valleys.
        if (longitude <= -69.45) return Kind.HIGH_MOUNTAIN;

        // Valle de Uco: broad operational box around Tupungato/Tunuyán/San Carlos.
        if (latitude <= -33.15 && latitude >= -34.15 && longitude <= -68.65 && longitude >= -69.45) {
            return Kind.VALLE_DE_UCO;
        }

        // Southern Mendoza, including the San Rafael / General Alvear / Malargüe belt.
        if (latitude < -34.15) return Kind.SOUTH;

        // Western Gran Mendoza / piedmont and precordillera corridor.
        if (latitude <= -32.45 && latitude >= -33.35 && longitude <= -68.95 && longitude > -69.45) {
            return Kind.PRECORDILLERA_PIEDEMONTE;
        }

        // Gran Mendoza urban/lowland operational box.
        if (latitude <= -32.55 && latitude >= -33.25 && longitude <= -68.50 && longitude > -68.95) {
            return Kind.GRAN_MENDOZA;
        }

        // Eastern lowlands. We require one additional hour of persistence here because
        // westerly flow alone is less specific for Zonda at these coordinates.
        if (longitude > -68.50 && latitude <= -32.25 && latitude >= -34.30) return Kind.EAST;

        return Kind.OTHER;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
