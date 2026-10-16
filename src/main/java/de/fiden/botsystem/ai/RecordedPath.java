package de.fiden.botsystem.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RecordedPath {
    public static final int GROUND = 1;
    public static final int JUMP = 2;
    public static final int SPRINT = 4;
    public static final int SNEAK = 8;
    public static final int CLIMB = 16;
    public static final int WATER = 32;
    public final String name;
    public final String world;
    public final double[] x;
    public final double[] y;
    public final double[] z;
    public final int[] flags;
    public final double[] s;
    private float[] left;
    private float[] right;
    private boolean reversed;

    public RecordedPath(String name, String world, List<double[]> points) {
        this.name = name;
        this.world = world;
        int n = points.size();
        this.x = new double[n];
        this.y = new double[n];
        this.z = new double[n];
        this.flags = new int[n];
        this.s = new double[n];

        for (int i = 0; i < n; i++) {
            double[] p = points.get(i);
            this.x[i] = p[0];
            this.y[i] = p[1];
            this.z[i] = p[2];
            this.flags[i] = p.length > 3 ? (int)p[3] : 1;
            if (i > 0) {
                double dx = this.x[i] - this.x[i - 1];
                double dy = this.y[i] - this.y[i - 1];
                double dz = this.z[i] - this.z[i - 1];
                this.s[i] = this.s[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
    }

    public RecordedPath reverse() {
        List<double[]> points = new ArrayList<>();
        int n = this.size();

        for (int i = n - 1; i >= 0; i--) {
            points.add(new double[]{this.x[i], this.y[i], this.z[i], this.flags[i] & -3});
        }

        for (int i = 0; i < n; i++) {
            if ((this.flags[i] & 2) != 0) {
                int landing = i + 1;

                while (landing < n - 1 && (this.flags[landing] & 1) == 0) {
                    landing++;
                }

                int mirrored = n - 1 - landing;
                double[] p = points.get(mirrored);
                p[3] = (int)p[3] | 2 | this.flags[i] & 4;
            }
        }

        RecordedPath back = new RecordedPath(this.name, this.world, points);
        back.reversed = !this.reversed;
        return back;
    }

    public boolean isReversed() {
        return this.reversed;
    }

    public int size() {
        return this.x.length;
    }

    public double length() {
        return this.s.length == 0 ? 0.0 : this.s[this.s.length - 1];
    }

    public Vec3 point(int i) {
        return new Vec3(this.x[i], this.y[i], this.z[i]);
    }

    public int segmentAt(double arc) {
        int lo = 0;
        int hi = this.s.length - 1;
        if (arc <= 0.0) {
            return 0;
        }

        if (arc >= this.s[hi]) {
            return Math.max(0, hi - 1);
        }

        while (hi - lo > 1) {
            int mid = lo + hi >>> 1;
            if (this.s[mid] <= arc) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        return lo;
    }

    public Vec3 at(double arc) {
        int n = this.size();
        if (n == 1) {
            return this.point(0);
        }

        arc = Mth.clamp(arc, 0.0, this.length());
        int i = this.segmentAt(arc);
        double span = this.s[i + 1] - this.s[i];
        double t = span < 1.0E-6 ? 0.0 : (arc - this.s[i]) / span;
        return new Vec3(Mth.lerp(t, this.x[i], this.x[i + 1]), Mth.lerp(t, this.y[i], this.y[i + 1]), Mth.lerp(t, this.z[i], this.z[i + 1]));
    }

    public Vec3 direction(double arc) {
        Vec3 a = this.at(arc - 1.5);
        Vec3 b = this.at(arc + 1.5);
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        return length < 0.001 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(dx / length, 0.0, dz / length);
    }

    public static Vec3 leftOf(Vec3 direction) {
        return new Vec3(direction.z, 0.0, -direction.x);
    }

    public boolean hasWidth() {
        return this.left != null;
    }

    public double leftAt(double arc) {
        return this.left == null ? 0.0 : this.left[Math.min(this.left.length - 1, this.nearestIndex(arc))];
    }

    public double rightAt(double arc) {
        return this.right == null ? 0.0 : this.right[Math.min(this.right.length - 1, this.nearestIndex(arc))];
    }

    public int nearestIndex(double arc) {
        int i = this.segmentAt(arc);
        return i + 1 < this.size() && arc - this.s[i] > this.s[i + 1] - arc ? i + 1 : i;
    }

    public double narrowest(double from, double to) {
        if (this.left == null) {
            return 0.0;
        }

        int a = this.nearestIndex(from);
        int b = this.nearestIndex(to);
        double best = Double.MAX_VALUE;

        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
            best = Math.min(best, this.left[i] + this.right[i]);
        }

        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    public void measure(Level world) {
        this.measure(world, 4.0);
    }

    public void measure(Level world, double maxReach) {
        int n = this.size();
        float[] l = new float[n];
        float[] r = new float[n];

        for (int i = 0; i < n; i++) {
            if ((this.flags[i] & 1) != 0 && (this.flags[i] & 48) == 0) {
                Vec3 dir = this.direction(this.s[i]);
                Vec3 leftDir = leftOf(dir);
                l[i] = (float)this.scan(world, i, leftDir, maxReach);
                r[i] = (float)this.scan(world, i, leftDir.scale(-1.0), maxReach);
            }
        }

        float[] l2 = (float[])l.clone();
        float[] r2 = (float[])r.clone();

        for (int i = 0; i < n; i++) {
            for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) {
                l2[i] = Math.min(l2[i], l[j] + 0.5F);
                r2[i] = Math.min(r2[i], r[j] + 0.5F);
            }
        }

        this.left = l2;
        this.right = r2;
    }

    private double scan(Level world, int i, Vec3 side, double maxReach) {
        double reach = 0.0;
        double refY = this.y[i];

        for (double k = 0.5; k <= maxReach; k += 0.5) {
            double px = this.x[i] + side.x * k;
            double pz = this.z[i] + side.z * k;
            double h = standHeight(world, px, pz, refY);
            if (Double.isNaN(h) || Math.abs(h - refY) > 0.55) {
                break;
            }

            reach = k;
            refY = h;
        }

        return reach;
    }

    public static double standHeight(Level world, double x, double z, double refY) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int top = Mth.floor(refY + 0.6);
        MutableBlockPos at = new MutableBlockPos();

        for (int level = top; level >= top - 2; level--) {
            at.set(bx, level, bz);
            BlockState state = world.getBlockState(at);
            VoxelShape shape = state.getCollisionShape(world, at);
            if (!shape.isEmpty()) {
                double h = level + shape.max(Axis.Y);
                if (h > refY + 0.6) {
                    return Double.NaN;
                }

                int feet = Mth.floor(h + 0.01);

                for (int up = feet; up <= feet + 1; up++) {
                    at.set(bx, up, bz);
                    if (!world.getBlockState(at).getCollisionShape(world, at).isEmpty()) {
                        return Double.NaN;
                    }
                }

                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                if (!id.contains("magma") && !id.contains("cactus") && !id.contains("campfire")) {
                    return h;
                }

                return Double.NaN;
            }

            if (!world.getFluidState(at).isEmpty() && level <= Mth.floor(refY)) {
                return Double.NaN;
            }
        }

        return Double.NaN;
    }

    public List<double[]> points() {
        List<double[]> list = new ArrayList<>(this.size());

        for (int i = 0; i < this.size(); i++) {
            list.add(new double[]{this.x[i], this.y[i], this.z[i], this.flags[i]});
        }

        return list;
    }
}
