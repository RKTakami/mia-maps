package com.mia.aperture.map;

// Pure camera->clip MVP helper for a future GPU orbit renderer. No GL, no Minecraft: just math.
//
// Handedness matches OrbitCamera / BeaconGeometry so a GPU pass lines up with the existing CPU
// projection. Camera basis is {right, up, forward} with forward pointing camera->focus; a point's
// view-space depth zc = (P-cam).forward is POSITIVE in front (same sign convention BeaconGeometry
// uses for its zc>0 "in front" test, where xc = P.right and yc = P.up). The perspective half maps
// that +depth eye space to clip w = zc, so w>0 means in front. Output is a COLUMN-MAJOR float[16]:
// element (row r, col c) lives at index c*4 + r, the layout GL/JOML consume directly.
public final class MapMatrix {
    private MapMatrix() {}

    public static float[] orbit(double focusX, double focusY, double focusZ,
                                double yawDeg, double pitchDeg, double distance,
                                float fovRad, float aspect, float near, float far) {
        OrbitCamera cam = new OrbitCamera(focusX, focusY, focusZ, yawDeg, pitchDeg, distance);
        double[] cel = cam.cameraPos();
        double[] b = cam.basis();
        double fx = b[0], fy = b[1], fz = b[2];
        double ux = b[3], uy = b[4], uz = b[5];
        double lx = b[6], ly = b[7], lz = b[8];
        double rx = -lx, ry = -ly, rz = -lz;

        double[][] view = {
            { rx, ry, rz, -(rx * cel[0] + ry * cel[1] + rz * cel[2]) },
            { ux, uy, uz, -(ux * cel[0] + uy * cel[1] + uz * cel[2]) },
            { fx, fy, fz, -(fx * cel[0] + fy * cel[1] + fz * cel[2]) },
            { 0,  0,  0,  1 },
        };

        double f = 1.0 / Math.tan(fovRad / 2.0);
        double a = (far + near) / (far - near);
        double bz = -2.0 * far * near / (far - near);
        // Negate Y: this MVP feeds the GPU renderer only (the CPU overlays use BeaconGeometry). GL
        // renders Y-up into the FBO but the result is sampled Y-down, so without this the GPU terrain
        // is mirrored vertically relative to the compass. Safe now that back-face cull is off.
        double[][] proj = {
            { f / aspect, 0, 0,  0  },
            { 0,         -f, 0,  0  },
            { 0,          0, a,  bz },
            { 0,          0, 1,  0  },
        };

        double[][] m = new double[4][4];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += proj[r][k] * view[k][c];
                m[r][c] = s;
            }

        float[] out = new float[16];
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++)
                out[c * 4 + r] = (float) m[r][c];
        return out;
    }

    public static float[] mul(float[] m16, double x, double y, double z, double w) {
        float[] v = { (float) x, (float) y, (float) z, (float) w };
        float[] out = new float[4];
        for (int r = 0; r < 4; r++) {
            double s = 0;
            for (int c = 0; c < 4; c++) s += m16[c * 4 + r] * v[c];
            out[r] = (float) s;
        }
        return out;
    }
}
