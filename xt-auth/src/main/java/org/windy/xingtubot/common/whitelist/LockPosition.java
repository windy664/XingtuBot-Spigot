package org.windy.xingtubot.common.whitelist;

/**
 * 三端共享的锁定坐标数据（原 Bukkit/Velocity/BungeeCord 各自逐字相同的 {@code LockData} 内部类合并而来）。
 * <p>从首个 Position 包捕获一次，供 {@link LockPacketListener} 拉回玩家用。
 */
public final class LockPosition {

    private volatile double x, y, z;
    private volatile float yaw, pitch;
    private volatile boolean hasPosition;

    public void setPosition(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.hasPosition = true;
    }

    public boolean hasPosition() { return hasPosition; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
}
