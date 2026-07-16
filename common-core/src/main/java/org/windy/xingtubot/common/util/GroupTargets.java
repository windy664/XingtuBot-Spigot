package org.windy.xingtubot.common.util;

import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.queue.KnownGroupStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Resolves extension group targets under the core allowed-groups boundary.
 *
 * <p>The core allowed-groups is the global boundary. An extension list may only
 * narrow that boundary. Empty or "*" means "do not narrow further".</p>
 */
public final class GroupTargets {

    private static final String CORE_ALLOWED_GROUPS = "core.allowed-groups";

    private GroupTargets() {
    }

    /** For notification-style pushes: if the effective scope is all, expand to KnownGroupStore. */
    public static List<String> resolveKnownGroups(ModuleContext ctx, List<String> extensionGroups) {
        return resolveKnownGroups(readCoreAllowedGroups(ctx), extensionGroups);
    }

    /** For notification-style pushes: if the effective scope is all, expand to KnownGroupStore. */
    public static List<String> resolveKnownGroups(XingtuBotHost host, List<String> extensionGroups) {
        return resolveKnownGroups(readCoreAllowedGroups(host), extensionGroups);
    }

    /** For chatlink-style pushes: return concrete groups only; all/all intentionally returns empty. */
    public static List<String> resolveConcrete(ModuleContext ctx, List<String> extensionGroups) {
        return resolveConcrete(readCoreAllowedGroups(ctx), extensionGroups);
    }

    /** For chatlink-style pushes: return concrete groups only; all/all intentionally returns empty. */
    public static List<String> resolveConcrete(XingtuBotHost host, List<String> extensionGroups) {
        return resolveConcrete(readCoreAllowedGroups(host), extensionGroups);
    }

    public static List<String> resolveKnownGroups(List<String> coreGroups, List<String> extensionGroups) {
        Scope core = Scope.of(coreGroups);
        Scope ext = Scope.of(extensionGroups);
        if (core.all && ext.all) {
            return new ArrayList<>(KnownGroupStore.getInstance().all());
        }
        return intersect(core, ext);
    }

    public static List<String> resolveConcrete(List<String> coreGroups, List<String> extensionGroups) {
        Scope core = Scope.of(coreGroups);
        Scope ext = Scope.of(extensionGroups);
        if (core.all && ext.all) {
            return Collections.emptyList();
        }
        return intersect(core, ext);
    }

    public static boolean isAllowedByCore(List<String> coreGroups, String groupOpenId) {
        if (groupOpenId == null || groupOpenId.trim().isEmpty()) return false;
        Scope core = Scope.of(coreGroups);
        return core.all || core.groups.contains(groupOpenId.trim());
    }

    public static List<String> readCoreAllowedGroups(ModuleContext ctx) {
        if (ctx == null) return Collections.singletonList("*");
        return readCoreAllowedGroups(ctx.getServiceObject(CORE_ALLOWED_GROUPS));
    }

    public static List<String> readCoreAllowedGroups(XingtuBotHost host) {
        if (host == null) return Collections.singletonList("*");
        return readCoreAllowedGroups(host.getServiceObject(CORE_ALLOWED_GROUPS));
    }

    private static List<String> readCoreAllowedGroups(Object service) {
        try {
            Object value = service;
            if (service instanceof Supplier) {
                value = ((Supplier<?>) service).get();
            }
            if (value instanceof Collection) {
                List<String> out = new ArrayList<>();
                for (Object item : (Collection<?>) value) {
                    if (item != null) out.add(String.valueOf(item));
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return Collections.singletonList("*");
    }

    private static List<String> intersect(Scope core, Scope ext) {
        if (core.all) {
            return new ArrayList<>(ext.groups);
        }
        if (ext.all) {
            return new ArrayList<>(core.groups);
        }
        List<String> out = new ArrayList<>();
        for (String g : ext.groups) {
            if (core.groups.contains(g)) out.add(g);
        }
        return out;
    }

    private static final class Scope {
        final boolean all;
        final List<String> groups;

        private Scope(boolean all, List<String> groups) {
            this.all = all;
            this.groups = groups;
        }

        static Scope of(List<String> raw) {
            boolean all = raw == null || raw.isEmpty();
            List<String> groups = new ArrayList<>();
            if (raw != null) {
                for (String g : raw) {
                    if (g == null) continue;
                    String trimmed = g.trim();
                    if (trimmed.isEmpty()) continue;
                    if ("*".equals(trimmed)) {
                        all = true;
                    } else if (!groups.contains(trimmed)) {
                        groups.add(trimmed);
                    }
                }
            }
            if (all) groups.clear();
            return new Scope(all, groups);
        }
    }
}
