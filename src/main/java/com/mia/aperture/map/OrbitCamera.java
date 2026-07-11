package com.mia.aperture.map;

public final class OrbitCamera {
    public double focusX, focusY, focusZ;
    public double yawDeg, pitchDeg, distance;

    public OrbitCamera(double focusX, double focusY, double focusZ,
                       double yawDeg, double pitchDeg, double distance) {
        this.focusX = focusX; this.focusY = focusY; this.focusZ = focusZ;
        this.yawDeg = yawDeg; this.pitchDeg = pitchDeg; this.distance = distance;
    }

    public double[] forward() {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        return new double[]{ -Math.sin(yaw) * cp, -sp, Math.cos(yaw) * cp };
    }

    public double[] cameraPos() {
        double[] f = forward();
        return new double[]{ focusX - f[0] * distance, focusY - f[1] * distance, focusZ - f[2] * distance };
    }

    public double[] basis() {
        double[] f = forward();
        double rrx = -f[2], rrz = f[0];
        double rl = Math.sqrt(rrx * rrx + rrz * rrz);
        if (rl < 1e-6) rl = 1;
        double rx = rrx / rl, rz = rrz / rl;
        double lx = -rx, lz = -rz;
        double ux = -rz * f[1], uy = rz * f[0] - rx * f[2], uz = rx * f[1];
        return new double[]{ f[0], f[1], f[2], ux, uy, uz, lx, 0, lz };
    }

    public BeaconGeometry.Screen project(double wx, double wy, double wz, double focal, int w, int h) {
        double[] cam = cameraPos();
        double[] b = basis();
        return BeaconGeometry.project(wx - cam[0], wy - cam[1], wz - cam[2],
                b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, w, h);
    }
}
