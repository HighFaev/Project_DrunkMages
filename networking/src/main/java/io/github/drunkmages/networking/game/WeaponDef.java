package io.github.drunkmages.networking.game;

public record WeaponDef(int itemType, String name, float fireRate, int damage, int projectiles, float spreadRadians, float bulletSpeed) {
    public static final WeaponDef PISTOL  = new WeaponDef(1, "Pistol",        0.40f, 20, 1, 0.05f, 240f);
    public static final WeaponDef SHOTGUN = new WeaponDef(3, "Shotgun",       1.00f, 15, 5, 0.25f, 200f);
    public static final WeaponDef AR      = new WeaponDef(4, "Assault Rifle", 0.15f, 18, 1, 0.10f, 300f);

    public static WeaponDef get(int type) {
        if (type == 3) return SHOTGUN;
        if (type == 4) return AR;
        return PISTOL;
    }
}