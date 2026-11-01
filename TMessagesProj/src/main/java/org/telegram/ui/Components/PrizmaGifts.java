package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;


import java.util.Random;

import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;

/**
 * Prizma: client-side profile "gift showcase". Lets the user pin any gifts from
 * the public catalog onto their own profile purely for decoration (visible only
 * to them — no real gift is created or received).
 */
public class PrizmaGifts {

    public static final int MAX = 150;

    /** A single showcase entry: which gift, and a unique instance id (so each copy upgrades independently). */
    public static class Inst {
        public long giftId;
        public long instId;
        Inst(long g, long i) { giftId = g; instId = i; }
    }

    private static long nextInst() {
        long v = PrizmaConfig.nftNextInst();
        PrizmaConfig.setNftNextInst(v - 1); // keep going more negative
        return v;
    }

    /** All showcase instances (order preserved). Migrates any legacy plain-id entries. */
    public static ArrayList<Inst> instances() {
        ArrayList<Inst> out = new ArrayList<>();
        String csv = PrizmaConfig.showcaseGifts();
        if (csv == null || csv.isEmpty()) {
            return out;
        }
        boolean rewrite = false;
        for (String part : csv.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                int c = part.indexOf(':');
                if (c > 0) {
                    out.add(new Inst(Long.parseLong(part.substring(0, c)), Long.parseLong(part.substring(c + 1))));
                } else {
                    // legacy: plain gift id → assign a fresh instance id
                    out.add(new Inst(Long.parseLong(part), nextInst()));
                    rewrite = true;
                }
            } catch (Throwable ignore) {}
        }
        if (rewrite) saveInstances(out);
        return out;
    }

    private static void saveInstances(ArrayList<Inst> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i).giftId).append(":").append(list.get(i).instId);
        }
        PrizmaConfig.setShowcaseGifts(sb.toString());
    }

    /** Showcase gift ids, duplicates allowed (used for isEmpty/legacy checks). */
    public static ArrayList<Long> ids() {
        ArrayList<Long> out = new ArrayList<>();
        for (Inst in : instances()) out.add(in.giftId);
        return out;
    }

    public static boolean contains(long giftId) {
        return count(giftId) > 0;
    }

    /** How many copies of this gift are in the showcase. */
    public static int count(long giftId) {
        int c = 0;
        for (Inst in : instances()) if (in.giftId == giftId) c++;
        return c;
    }

    /** True if this is a live showcase instance id (a per-copy saved_id we injected). */
    public static boolean isInstance(long instId) {
        for (Inst in : instances()) if (in.instId == instId) return true;
        return false;
    }

    /** The gift id behind an instance id, or 0 if unknown. */
    public static long giftIdOf(long instId) {
        for (Inst in : instances()) if (in.instId == instId) return in.giftId;
        return 0;
    }

    /** Adds one more copy of the gift (its own instance). Returns false if the showcase is full. */
    public static boolean add(long giftId) {
        ArrayList<Inst> list = instances();
        if (list.size() >= MAX) return false;
        list.add(new Inst(giftId, nextInst()));
        saveInstances(list);
        return true;
    }

    /** Adds one more copy and returns its instance id (0 if the showcase is full). */
    public static long addInstance(long giftId) {
        ArrayList<Inst> list = instances();
        if (list.size() >= MAX) return 0;
        long inst = nextInst();
        list.add(new Inst(giftId, inst));
        saveInstances(list);
        return inst;
    }

    /** Removes exactly this copy (crafting burns copies) and forgets its NFT look. */
    public static void removeInstance(long instId) {
        ArrayList<Inst> list = instances();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).instId == instId) {
                list.remove(i);
                saveInstances(list);
                break;
            }
        }
        setUpgraded(instId, false);
        PrizmaConfig.setNftCrafted(instId, false);
    }

    /** True when every copy of this gift in the showcase is an NFT (removing one loses its look). */
    public static boolean onlyUpgradedCopies(long giftId) {
        boolean any = false;
        for (Inst in : instances()) {
            if (in.giftId != giftId) continue;
            if (!isUpgraded(in.instId)) return false;
            any = true;
        }
        return any;
    }

    /**
     * Removes a single copy and clears that instance's upgrade state. A plain copy goes first — the
     * long press in the catalog used to throw away the newest rolled NFT.
     */
    public static void removeOne(long giftId) {
        ArrayList<Inst> list = instances();
        int pick = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).giftId != giftId) continue;
            if (pick < 0) pick = i;
            if (!isUpgraded(list.get(i).instId)) {
                pick = i;
                break;
            }
        }
        if (pick < 0) return;
        long inst = list.get(pick).instId;
        list.remove(pick);
        saveInstances(list);
        setUpgraded(inst, false);
        PrizmaConfig.setNftCrafted(inst, false);
    }

    /** Removes every copy of this gift and clears their upgrade state. */
    public static void removeAll(long giftId) {
        ArrayList<Inst> list = instances();
        ArrayList<Inst> out = new ArrayList<>();
        for (Inst in : list) {
            if (in.giftId == giftId) {
                setUpgraded(in.instId, false);
            } else {
                out.add(in);
            }
        }
        saveInstances(out);
    }

    // ===== per-gift pseudo-NFT "upgrade" (visual, client-side) =====

    private static ArrayList<Long> upgradedIds() {
        ArrayList<Long> out = new ArrayList<>();
        String csv = PrizmaConfig.pseudoNftIds();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (!out.contains(id)) out.add(id);
            } catch (Throwable ignore) {}
        }
        return out;
    }

    public static boolean isUpgraded(long id) {
        return upgradedIds().contains(id);
    }

    public static void setUpgraded(long id, boolean upgraded) {
        ArrayList<Long> list = upgradedIds();
        if (upgraded) {
            if (!list.contains(id)) list.add(id);
        } else {
            list.remove(id);
            PrizmaConfig.setNftAttr(id, ""); // forget real attrs when un-upgrading
            PrizmaConfig.setNftNum(id, "");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        PrizmaConfig.setPseudoNftIds(sb.toString());
    }

    public static boolean isFull() {
        return instances().size() >= MAX;
    }

    /** The public gift catalog (loads it if needed). */
    public static ArrayList<TL_stars.StarGift> catalog() {
        StarsController c = StarsController.getInstance(UserConfig.selectedAccount);
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            c.loadStarGifts();
        } else {
            // gift cards are built in MessageObject's constructor, often off the UI thread —
            // StarsController's loading state is UI-thread only
            AndroidUtilities.runOnUIThread(c::loadStarGifts);
        }
        return c.gifts;
    }

    public static TL_stars.StarGift find(long id) {
        ArrayList<TL_stars.StarGift> list = catalog();
        // by index, not an iterator: off the UI thread the catalog may be refilled meanwhile
        for (int i = 0; i < list.size(); i++) {
            TL_stars.StarGift g;
            try {
                g = list.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (g != null && g.id == id) {
                return g;
            }
        }
        return null;
    }

    /**
     * Injects the user's chosen showcase gifts (as fake SavedStarGift entries) into the
     * profile gifts list, but only for the user's OWN profile. Returns how many were added.
     * Called from StarsController.GiftsList.load() after the server page is applied.
     */
    public static int injectShowcase(int account, long dialogId, ArrayList<TL_stars.SavedStarGift> gifts) {
        long self = UserConfig.getInstance(account).getClientUserId();
        if (!(dialogId == 0 || dialogId == self)) {
            return dialogId > 0 ? injectRemote(dialogId, gifts) : 0;
        }
        org.telegram.messenger.PrizmaCloud.syncShowcaseSoon(account);
        ArrayList<Inst> list = instances();
        if (list.isEmpty()) {
            return 0;
        }
        ArrayList<String> order = pinOrder();
        catalog(); // make sure the catalog load is triggered
        ensureCatalogObserver(account); // on cold start the catalog may not be ready yet → reload when it is
        int added = 0;
        // prepend in chosen order (iterate reversed so first chosen ends up first)
        for (int i = list.size() - 1; i >= 0; i--) {
            Inst in = list.get(i);
            TL_stars.StarGift g = find(in.giftId);
            if (g == null) {
                continue;
            }
            TL_stars.TL_savedStarGift s = new TL_stars.TL_savedStarGift();
            s.gift = isUpgraded(in.instId) ? makePseudoUnique(g, in.instId) : g;
            s.unsaved = false;
            s.date = (int) (System.currentTimeMillis() / 1000);
            s.saved_id = in.instId; // unique per copy → each upgrades independently
            s.pinned_to_top = order.contains("p" + in.instId);
            gifts.add(0, s);
            added++;
        }
        arrange(gifts, order);
        return added;
    }

    private static boolean catalogObserverAdded = false;

    /** Once the gift catalog finishes loading, re-inject showcase gifts into the own profile list. */
    private static void ensureCatalogObserver(int account) {
        if (catalogObserverAdded) return;
        catalogObserverAdded = true;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(
                        (id, acc, args) -> {
                            if (id == org.telegram.messenger.NotificationCenter.starGiftsLoaded && !ids().isEmpty()) {
                                long self = UserConfig.getInstance(acc).getClientUserId();
                                StarsController.getInstance(acc).getProfileGiftsList(self).invalidate(true);
                            }
                        },
                        org.telegram.messenger.NotificationCenter.starGiftsLoaded);
            } catch (Throwable ignore) {}
        });
    }

    // Fake "unique/NFT" palettes: {center, edge, pattern, text} as RGB
    public static final int[][] NFT_PALETTE = {
            {0x6A5BFF, 0x3CC8FF, 0xFFFFFF, 0xFFFFFF},
            {0xFF6FD8, 0x8A5CFF, 0xFFFFFF, 0xFFFFFF},
            {0x34E0A1, 0x3FB6F0, 0xFFFFFF, 0xFFFFFF},
            {0xFF9A5A, 0xB15BFF, 0xFFFFFF, 0xFFFFFF},
            {0xFF3CAC, 0x2B86C5, 0xFFFFFF, 0xFFFFFF},
            {0xFFD24A, 0xFF7A18, 0x1A1A1A, 0xFFFFFF},
            {0x00C2A8, 0x0E8F7E, 0xFFFFFF, 0xFFFFFF},
            {0xE64B7A, 0x7A1FA2, 0xFFFFFF, 0xFFFFFF},
            {0x2E6BFF, 0x23D5C9, 0xFFFFFF, 0xFFFFFF},
            {0x9E9E9E, 0x404040, 0xFFFFFF, 0xFFFFFF},
    };

    public static final String[] NFT_NAMES = {
            "Призма", "Аметист", "Изумруд", "Закат", "Фуксия",
            "Золото", "Нефрит", "Рубин", "Океан", "Графит"
    };

    // ===== chosen backdrop style per gift =====

    private static java.util.HashMap<Long, Integer> styleMap() {
        java.util.HashMap<Long, Integer> m = new java.util.HashMap<>();
        String csv = PrizmaConfig.nftStyles();
        if (csv == null || csv.isEmpty()) return m;
        for (String part : csv.split(",")) {
            try {
                String[] kv = part.split(":");
                if (kv.length == 2) {
                    m.put(Long.parseLong(kv[0].trim()), Integer.parseInt(kv[1].trim()));
                }
            } catch (Throwable ignore) {}
        }
        return m;
    }

    /** The chosen style index for a gift (defaults to one derived from its id). */
    public static int style(long id) {
        Integer v = styleMap().get(id);
        int idx = v != null ? v : (int) (Math.abs(id) % NFT_PALETTE.length);
        if (idx < 0) idx = 0;
        if (idx >= NFT_PALETTE.length) idx = NFT_PALETTE.length - 1;
        return idx;
    }

    public static void setStyle(long id, int idx) {
        java.util.HashMap<Long, Integer> m = styleMap();
        m.put(id, idx);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<Long, Integer> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(e.getKey()).append(":").append(e.getValue());
            first = false;
        }
        PrizmaConfig.setNftStyles(sb.toString());
    }

    // ===== real upgrade attributes (from the server, like original Telegram) =====

    /** Serialize the 3 chosen real attributes so the look persists across sessions. */
    static void storeChosenAttrs(long giftId, TL_stars.StarGiftAttribute model, TL_stars.StarGiftAttribute backdrop, TL_stars.StarGiftAttribute pattern) {
        try {
            ArrayList<TL_stars.StarGiftAttribute> list = new ArrayList<>();
            if (model != null) list.add(model);
            if (backdrop != null) list.add(backdrop);
            if (pattern != null) list.add(pattern);
            int size = 8;
            for (TL_stars.StarGiftAttribute a : list) size += a.getObjectSize();
            NativeByteBuffer buf = new NativeByteBuffer(size);
            buf.writeInt32(list.size());
            for (TL_stars.StarGiftAttribute a : list) a.serializeToStream(buf);
            int len = buf.buffer.position();
            byte[] bytes = new byte[len];
            buf.buffer.position(0);
            buf.buffer.get(bytes, 0, len);
            buf.reuse();
            PrizmaConfig.setNftAttr(giftId, Base64.encodeToString(bytes, Base64.NO_WRAP));
        } catch (Throwable ignore) {}
    }

    static ArrayList<TL_stars.StarGiftAttribute> loadChosenAttrs(long giftId) {
        return decodeAttrs(PrizmaConfig.nftAttr(giftId));
    }

    /** The 3 upgrade attributes from their base64 form (as stored or as sent by another Libre). */
    public static ArrayList<TL_stars.StarGiftAttribute> decodeAttrs(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] data = Base64.decode(b64, Base64.NO_WRAP);
            NativeByteBuffer buf = new NativeByteBuffer(data.length);
            buf.writeBytes(data);
            buf.position(0);
            int n = buf.readInt32(false);
            ArrayList<TL_stars.StarGiftAttribute> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                TL_stars.StarGiftAttribute a = TL_stars.StarGiftAttribute.TLdeserialize(buf, buf.readInt32(false), false);
                if (a != null) out.add(a);
            }
            buf.reuse();
            return out.isEmpty() ? null : out;
        } catch (Throwable e) {
            return null;
        }
    }

    // ===== crafting (Telegram's craft screen; the result is decided here, on the device) =====

    /** Each copy put into the forge adds 25% — four copies always succeed. */
    public static final int CRAFT_CHANCE_PERMILLE = 250;

    /** Upgraded showcase copies of one collection, as NFT-looking gifts (for the craft picker). */
    public static ArrayList<TL_stars.TL_starGiftUnique> craftCandidates(long collectionId, java.util.Set<Long> exclude) {
        ArrayList<TL_stars.TL_starGiftUnique> out = new ArrayList<>();
        TL_stars.StarGift base = find(collectionId);
        if (base == null) return out;
        for (Inst in : instances()) {
            if (in.giftId != collectionId || !isUpgraded(in.instId) || exclude != null && exclude.contains(in.instId)) continue;
            if (PrizmaConfig.nftCrafted(in.instId)) continue; // crafted gifts can't go back into the forge
            TL_stars.StarGift g = makePseudoUnique(base, in.instId);
            // the forge screen needs a model, a backdrop and a symbol on every gift it shows
            if (g instanceof TL_stars.TL_starGiftUnique && isCompleteSample(g.attributes)) out.add((TL_stars.TL_starGiftUnique) g);
        }
        return out;
    }

    /**
     * Burns the given copies and, with their summed chance, forges a new one: a crafting-only model
     * of the collection (rarer = less likely), backdrop and symbol inherited from the inputs
     * (more copies with the same one = more likely). Returns the new gift, or null if it failed.
     */
    public static TL_stars.TL_starGiftUnique craft(ArrayList<TL_stars.StarGift> inputs, ArrayList<TL_stars.StarGiftAttribute> collectionAttrs) {
        if (inputs == null || inputs.isEmpty()) return null;
        long collectionId = inputs.get(0).gift_id;
        TL_stars.StarGift base = find(collectionId);
        if (base == null) return null; // StarGiftSheet checks canCraft() first, so nothing burns for nothing
        int chance = 0;
        ArrayList<TL_stars.StarGiftAttribute> backdrops = new ArrayList<>();
        ArrayList<TL_stars.StarGiftAttribute> patterns = new ArrayList<>();
        ArrayList<TL_stars.StarGiftAttribute> inputModels = new ArrayList<>();
        for (TL_stars.StarGift g : inputs) {
            chance += g.craft_chance_permille;
            if (g instanceof TL_stars.TL_starGiftUnique) {
                for (TL_stars.StarGiftAttribute a : ((TL_stars.TL_starGiftUnique) g).attributes) {
                    if (a instanceof TL_stars.starGiftAttributeBackdrop) backdrops.add(a);
                    else if (a instanceof TL_stars.starGiftAttributePattern) patterns.add(a);
                    else if (a instanceof TL_stars.starGiftAttributeModel) inputModels.add(a);
                }
            }
            removeInstance(g.id); // the forge takes them, success or not — like in Telegram
        }
        org.telegram.messenger.PrizmaCloud.syncShowcaseSoon(UserConfig.selectedAccount); // burned copies leave the profile for others too
        Random rnd = new Random();
        if (rnd.nextInt(1000) >= Math.min(1000, chance)) {
            return null;
        }
        // crafting-only models are the ones without a plain percentage rarity
        ArrayList<TL_stars.StarGiftAttribute> models = new ArrayList<>();
        if (collectionAttrs != null) {
            for (TL_stars.StarGiftAttribute a : collectionAttrs) {
                if (a instanceof TL_stars.starGiftAttributeModel && ((TL_stars.starGiftAttributeModel) a).document != null
                        && !(a.rarity instanceof TL_stars.TL_starGiftAttributeRarity)) {
                    models.add(a);
                }
            }
            if (models.isEmpty()) {
                for (TL_stars.StarGiftAttribute a : collectionAttrs) {
                    if (a instanceof TL_stars.starGiftAttributeModel && ((TL_stars.starGiftAttributeModel) a).document != null) models.add(a);
                }
            }
        }
        if (models.isEmpty()) models = inputModels;
        TL_stars.StarGiftAttribute model = pickWeighted(models, TL_stars.starGiftAttributeModel.class, rnd);
        TL_stars.StarGiftAttribute backdrop = backdrops.isEmpty() ? null : backdrops.get(rnd.nextInt(backdrops.size()));
        TL_stars.StarGiftAttribute pattern = patterns.isEmpty() ? null : patterns.get(rnd.nextInt(patterns.size()));
        if (model == null || backdrop == null || pattern == null) {
            return null;
        }
        long inst = addInstance(collectionId); // only now — a failed forge must not leave a plain copy behind
        if (inst == 0) {
            return null;
        }
        PrizmaConfig.setNftCrafted(inst, true);
        TL_stars.StarGift result = applyChosen(inst, base, model, backdrop, pattern);
        return result instanceof TL_stars.TL_starGiftUnique ? (TL_stars.TL_starGiftUnique) result : null;
    }

    // ===== free "upgrade to NFT" (driven by StarGiftSheet's native upgrade page) =====

    /** True when the server preview has everything the upgrade page needs (models, backdrops, symbols). */
    public static boolean isCompleteSample(ArrayList<TL_stars.StarGiftAttribute> attrs) {
        if (attrs == null) return false;
        boolean model = false, backdrop = false, pattern = false;
        for (TL_stars.StarGiftAttribute a : attrs) {
            if (a instanceof TL_stars.starGiftAttributeModel && ((TL_stars.starGiftAttributeModel) a).document != null) model = true;
            else if (a instanceof TL_stars.starGiftAttributeBackdrop) backdrop = true;
            else if (a instanceof TL_stars.starGiftAttributePattern && ((TL_stars.starGiftAttributePattern) a).document != null) pattern = true;
        }
        return model && backdrop && pattern;
    }

    /** Sample attributes for gifts without an NFT version: the gift's own sticker + our backdrop palette. */
    public static ArrayList<TL_stars.StarGiftAttribute> fallbackSampleAttributes(TL_stars.StarGift g) {
        ArrayList<TL_stars.StarGiftAttribute> out = new ArrayList<>();
        TLRPC.Document doc = g != null ? g.getDocument() : null;
        if (doc == null) return out;
        TL_stars.starGiftAttributeModel model = new TL_stars.starGiftAttributeModel();
        model.name = g.title != null && !g.title.isEmpty() ? g.title : "Libre";
        model.document = doc;
        model.rarity = rarity(1000);
        out.add(model);
        for (int i = 0; i < NFT_PALETTE.length; i++) {
            TL_stars.starGiftAttributeBackdrop bd = new TL_stars.starGiftAttributeBackdrop();
            bd.name = NFT_NAMES[i];
            bd.backdrop_id = 9000 + i;
            bd.center_color = NFT_PALETTE[i][0];
            bd.edge_color = NFT_PALETTE[i][1];
            bd.pattern_color = NFT_PALETTE[i][2];
            bd.text_color = NFT_PALETTE[i][3];
            bd.rarity = rarity(i < 2 ? 50 : 100); // the first two are a bit rarer
            out.add(bd);
        }
        TL_stars.starGiftAttributePattern pat = new TL_stars.starGiftAttributePattern();
        pat.name = model.name;
        pat.document = doc;
        pat.rarity = rarity(1000);
        out.add(pat);
        return out;
    }

    /**
     * Rolls the final look like a real upgrade (rarer attributes drop less often), stores it for this
     * showcase copy and returns the unique (NFT-looking) gift to show.
     */
    public static TL_stars.StarGift rollUpgrade(long instId, TL_stars.StarGift base, ArrayList<TL_stars.StarGiftAttribute> samples) {
        Random rnd = new Random();
        TL_stars.StarGiftAttribute model = pickWeighted(samples, TL_stars.starGiftAttributeModel.class, rnd);
        TL_stars.StarGiftAttribute backdrop = pickWeighted(samples, TL_stars.starGiftAttributeBackdrop.class, rnd);
        TL_stars.StarGiftAttribute pattern = pickWeighted(samples, TL_stars.starGiftAttributePattern.class, rnd);
        if (model == null || backdrop == null || pattern == null) {
            return base;
        }
        storeChosenAttrs(instId, model, backdrop, pattern);
        rollNumber(instId, base);
        setUpgraded(instId, true);
        return makePseudoUnique(base, instId);
    }

    /** The plain catalogue gift behind a showcase copy (also when that copy already looks like an NFT). */
    public static TL_stars.StarGift baseGift(long instId) {
        return find(giftIdOf(instId));
    }

    /**
     * Constructor: the user picked exactly this model, backdrop and symbol. Stores them for this copy
     * (keeps its number if it already had one) and returns the NFT-looking gift.
     */
    public static TL_stars.StarGift applyChosen(long instId, TL_stars.StarGift base,
                                                TL_stars.StarGiftAttribute model, TL_stars.StarGiftAttribute backdrop, TL_stars.StarGiftAttribute pattern) {
        if (base == null || model == null || backdrop == null || pattern == null) {
            return base;
        }
        storeChosenAttrs(instId, model, backdrop, pattern);
        String num = PrizmaConfig.nftNum(instId);
        if (num == null || num.isEmpty()) {
            rollNumber(instId, base);
        }
        setUpgraded(instId, true);
        return makePseudoUnique(base, instId);
    }

    private static TL_stars.StarGiftAttribute pickWeighted(ArrayList<TL_stars.StarGiftAttribute> all, Class<?> type, Random rnd) {
        if (all == null) return null;
        ArrayList<TL_stars.StarGiftAttribute> list = new ArrayList<>();
        long total = 0;
        for (TL_stars.StarGiftAttribute a : all) {
            if (a != null && type.isInstance(a)) {
                list.add(a);
                total += weight(a);
            }
        }
        if (list.isEmpty()) return null;
        long r = (long) (rnd.nextDouble() * total);
        for (TL_stars.StarGiftAttribute a : list) {
            r -= weight(a);
            if (r < 0) return a;
        }
        return list.get(list.size() - 1);
    }

    private static int weight(TL_stars.StarGiftAttribute a) {
        TL_stars.StarGiftAttributeRarity r = a.rarity;
        if (r instanceof TL_stars.TL_starGiftAttributeRarity) {
            return Math.max(1, ((TL_stars.TL_starGiftAttributeRarity) r).permille);
        }
        if (r instanceof TL_stars.TL_starGiftAttributeRarityUncommon) return 60;
        if (r instanceof TL_stars.TL_starGiftAttributeRarityRare) return 20;
        if (r instanceof TL_stars.TL_starGiftAttributeRarityEpic) return 6;
        if (r instanceof TL_stars.TL_starGiftAttributeRarityLegendary) return 2;
        return 10;
    }

    // realistic edition sizes for unlimited gifts (limited gifts use their own availability_total)
    private static final int[] NFT_TOTALS = {2000, 3000, 5000, 8000, 10000, 15000, 25000, 50000};

    /** The real-looking total supply "in the world": the gift's own limited supply, else a realistic edition size. */
    private static int supplyTotal(TL_stars.StarGift g) {
        if (g != null && g.availability_total > 0) {
            return g.availability_total; // real supply of this limited gift
        }
        long seed = g != null ? Math.abs(g.id) : 0;
        return NFT_TOTALS[(int) (seed % NFT_TOTALS.length)];
    }

    /** Draws and persists a random "#num of total" for this instance, using the gift's real supply. */
    static void rollNumber(long instId, TL_stars.StarGift g) {
        Random r = new Random();
        int total = supplyTotal(g);
        int num = 1 + r.nextInt(Math.max(1, total));
        PrizmaConfig.setNftNum(instId, num + "/" + total);
    }

    /** Returns {num, total} for an instance, generating a stable fallback if none was rolled. */
    static int[] number(long instId, TL_stars.StarGift g) {
        return parseNumber(PrizmaConfig.nftNum(instId), instId, g);
    }

    static int[] parseNumber(String s, long instId, TL_stars.StarGift g) {
        if (s != null && s.contains("/")) {
            try {
                String[] p = s.split("/");
                return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
            } catch (Throwable ignore) {}
        }
        int total = supplyTotal(g);
        int num = (int) (Math.abs(instId) % Math.max(1, total)) + 1;
        return new int[]{num, total};
    }

    private static TL_stars.StarGiftAttributeRarity rarity(int permille) {
        TL_stars.TL_starGiftAttributeRarity r = new TL_stars.TL_starGiftAttributeRarity();
        r.permille = permille;
        return r;
    }

    /** Wraps a regular gift into a fake unique (NFT-looking) gift — purely visual, per instance. */
    public static TL_stars.StarGift makePseudoUnique(TL_stars.StarGift g, long instId) {
        return buildUnique(g, instId, loadChosenAttrs(instId), number(instId, g), style(instId), PrizmaConfig.nftCrafted(instId), null);
    }

    /**
     * An NFT-looking gift from its parts — our own copies (from prefs) and other people's (from
     * Libre Cloud) go through the same code, so they look identical. {@code g} may be null when
     * only the attributes are known (the gift catalog hasn't loaded yet).
     */
    public static TL_stars.StarGift buildUnique(TL_stars.StarGift g, long instId, ArrayList<TL_stars.StarGiftAttribute> real,
                                                int[] nt, int styleIdx, boolean crafted, String fallbackTitle) {
        try {
            TLRPC.Document doc = g != null ? g.getDocument() : null;
            TL_stars.starGiftAttributeModel realModel0 = null;
            if (real != null) {
                for (TL_stars.StarGiftAttribute a : real) {
                    if (a instanceof TL_stars.starGiftAttributeModel) realModel0 = (TL_stars.starGiftAttributeModel) a;
                }
            }
            if (doc == null && (realModel0 == null || realModel0.document == null)) {
                return g;
            }
            TL_stars.TL_starGiftUnique u = new TL_stars.TL_starGiftUnique();
            u.id = instId;     // one id per copy — crafting and the profile grid tell copies apart by it
            u.gift_id = g != null ? g.id : 0;  // the collection
            u.title = g != null && g.title != null && !g.title.isEmpty() ? g.title : fallbackTitle != null && !fallbackTitle.isEmpty() ? fallbackTitle : "Libre";
            u.slug = "libre" + Math.abs(instId);
            u.crafted = crafted;
            u.craft_chance_permille = CRAFT_CHANCE_PERMILLE;
            u.num = nt[0];
            u.availability_issued = nt[0];
            u.availability_total = nt[1];
            u.attributes = new ArrayList<>();

            // Prefer the REAL attributes the user rolled (identical to original Telegram).
            TL_stars.starGiftAttributeModel realModel = null;
            if (real != null) {
                for (TL_stars.StarGiftAttribute a : real) {
                    if (a instanceof TL_stars.starGiftAttributeModel) realModel = (TL_stars.starGiftAttributeModel) a;
                }
            }
            if (real != null && realModel != null && realModel.document != null) {
                u.attributes.addAll(real);
                return u;
            }

            // Fallback: fake look from the built-in palette.
            TL_stars.starGiftAttributeModel model = new TL_stars.starGiftAttributeModel();
            model.name = u.title;
            model.document = doc;
            model.rarity = rarity(15);
            u.attributes.add(model);

            int[] pal = NFT_PALETTE[Math.max(0, Math.min(NFT_PALETTE.length - 1, styleIdx))];
            TL_stars.starGiftAttributeBackdrop bd = new TL_stars.starGiftAttributeBackdrop();
            bd.name = "Prizma";
            bd.backdrop_id = (int) (Math.abs(instId) % 100);
            bd.center_color = pal[0];
            bd.edge_color = pal[1];
            bd.pattern_color = pal[2];
            bd.text_color = pal[3];
            bd.rarity = rarity(15);
            u.attributes.add(bd);

            TL_stars.starGiftAttributePattern pat = new TL_stars.starGiftAttributePattern();
            pat.name = "Prizma";
            pat.document = doc;
            pat.rarity = rarity(15);
            u.attributes.add(pat);

            return u;
        } catch (Throwable e) {
            return g;
        }
    }

    // ===== pinning (Telegram's server can't pin our copies, so the order lives here) =====

    /** A stable key for any gift in the profile list: "p<inst>" ours, "m<msg>" / "s<saved>" Telegram's. */
    public static String pinKey(TL_stars.SavedStarGift g) {
        if (isInstance(g.saved_id) || isRemoteId(g.saved_id)) return "p" + g.saved_id;
        return (g.flags & 8) != 0 ? "m" + g.msg_id : "s" + g.saved_id;
    }

    private static ArrayList<String> pinOrder() {
        ArrayList<String> out = new ArrayList<>();
        String csv = PrizmaConfig.showcasePins();
        if (csv == null || csv.isEmpty()) return out;
        for (String k : csv.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** StarsController.sendPinnedOrder for the own profile: remember the whole pinned order. */
    public static void savePinOrder(ArrayList<TL_stars.SavedStarGift> pinned) {
        StringBuilder sb = new StringBuilder();
        for (TL_stars.SavedStarGift g : pinned) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pinKey(g));
        }
        PrizmaConfig.setShowcasePins(sb.toString());
    }

    /** True for gifts that exist only in Libre — Telegram's server must never hear about them. */
    public static boolean isLibreGift(TL_stars.SavedStarGift g) {
        return g != null && (isInstance(g.saved_id) || isRemoteId(g.saved_id));
    }

    /** Pinned first (in their order), then Libre's copies, then the rest as Telegram sent it. */
    private static void arrange(ArrayList<TL_stars.SavedStarGift> gifts, ArrayList<String> order) {
        ArrayList<TL_stars.SavedStarGift> pinned = new ArrayList<>(), ours = new ArrayList<>(), rest = new ArrayList<>();
        for (TL_stars.SavedStarGift g : gifts) {
            if (g.pinned_to_top) pinned.add(g);
            else if (isLibreGift(g)) ours.add(g);
            else rest.add(g);
        }
        if (order != null && !order.isEmpty()) {
            java.util.Collections.sort(pinned, (a, b) -> {
                int ia = order.indexOf(pinKey(a)), ib = order.indexOf(pinKey(b));
                return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
            });
        }
        gifts.clear();
        gifts.addAll(pinned);
        gifts.addAll(ours);
        gifts.addAll(rest);
    }

    // ===== other Libre users' showcases (from Libre Cloud) =====

    private static final long REMOTE_BASE = -4_000_000_000_000_000L;

    /** Ids we give to other people's copies — far away from our own instance ids. */
    public static boolean isRemoteId(long id) {
        return id <= REMOTE_BASE;
    }

    /** One entry of someone's showcase as Libre Cloud sends it. */
    public static class RemoteGift {
        public long giftId;
        public String attr, num, title;
        public int style;
        public boolean crafted;
        public int pinned = -1; // position among pinned, -1 = not pinned
    }

    private static TL_stars.SavedStarGift remoteSaved(long ownerId, int index, RemoteGift r) {
        TL_stars.StarGift base = find(r.giftId);
        long id = REMOTE_BASE - (Math.abs(ownerId) % 1_000_000_000_000L) * 200 - index;
        int[] nt = parseNumber(r.num, id, base != null ? base : new TL_stars.TL_starGift());
        TL_stars.StarGift look = buildUnique(base, id, decodeAttrs(r.attr), nt, r.style, r.crafted, r.title);
        if (!(look instanceof TL_stars.TL_starGiftUnique)) return null;
        TL_stars.TL_savedStarGift s = new TL_stars.TL_savedStarGift();
        s.gift = look;
        s.unsaved = false;
        s.date = (int) (System.currentTimeMillis() / 1000);
        s.saved_id = id;
        s.pinned_to_top = r.pinned >= 0;
        return s;
    }

    /** Puts someone's Libre NFTs into their profile list. Returns how many were added. */
    private static int injectRemote(long ownerId, ArrayList<TL_stars.SavedStarGift> gifts) {
        ArrayList<RemoteGift> list = org.telegram.messenger.PrizmaCloud.showcaseOf(ownerId);
        if (list == null || list.isEmpty()) return 0;
        catalog();
        ArrayList<TL_stars.SavedStarGift> mine = new ArrayList<>();
        final java.util.HashMap<TL_stars.SavedStarGift, Integer> pinPos = new java.util.HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            RemoteGift r = list.get(i);
            TL_stars.SavedStarGift s = remoteSaved(ownerId, i, r);
            if (s == null) continue;
            mine.add(s);
            if (r.pinned >= 0) pinPos.put(s, r.pinned);
        }
        // their pinned copies keep their order
        ArrayList<TL_stars.SavedStarGift> pinned = new ArrayList<>(pinPos.keySet());
        java.util.Collections.sort(pinned, (x, y) -> Integer.compare(pinPos.get(x), pinPos.get(y)));
        ArrayList<String> order = new ArrayList<>();
        for (TL_stars.SavedStarGift s : pinned) order.add(pinKey(s));
        gifts.addAll(0, mine);
        arrange(gifts, order);
        return mine.size();
    }

    /** What goes up to Libre Cloud: my upgraded copies, in showcase order, with their pin position. */
    public static org.json.JSONArray showcaseForCloud() {
        org.json.JSONArray arr = new org.json.JSONArray();
        ArrayList<String> order = pinOrder();
        try {
            for (Inst in : instances()) {
                if (!isUpgraded(in.instId)) continue;
                TL_stars.StarGift g = find(in.giftId);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("inst", in.instId);
                o.put("giftId", in.giftId);
                o.put("attr", PrizmaConfig.nftAttr(in.instId));
                o.put("num", PrizmaConfig.nftNum(in.instId));
                o.put("style", style(in.instId));
                o.put("crafted", PrizmaConfig.nftCrafted(in.instId));
                if (g != null && g.title != null) o.put("title", g.title);
                o.put("pinned", order.indexOf("p" + in.instId));
                arr.put(o);
            }
        } catch (Throwable ignore) {
        }
        return arr;
    }

    public static void applySticker(ImageReceiver receiver, TL_stars.StarGift gift) {
        if (receiver == null || gift == null) return;
        TLRPC.Document document = gift.getDocument();
        if (document == null) return;
        TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, dp(100));
        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(document, Theme.key_windowBackgroundGray, 0.3f);
        receiver.setImage(
                ImageLocation.getForDocument(document), "60_60_nolimit_pcache",
                ImageLocation.getForDocument(photoSize, document), "60_60_nolimit_pcache",
                svgThumb, gift, 0);
    }

    public static void applySticker(BackupImageView view, TL_stars.StarGift gift) {
        applySticker(view, gift, true);
    }

    /**
     * animated = false draws only the first frame: the catalog picker shows 100+ gifts at once
     * (not a recycled list), and animating all of them — plus building their frame caches — lagged.
     */
    public static void applySticker(BackupImageView view, TL_stars.StarGift gift, boolean animated) {
        if (view == null || gift == null) return;
        TLRPC.Document document = gift.getDocument();
        if (document == null) return;
        TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, dp(100));
        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(document, Theme.key_windowBackgroundGray, 0.3f);
        final String filter = animated ? "80_80_nolimit_pcache" : "80_80_firstframe";
        view.setImage(
                ImageLocation.getForDocument(document), filter,
                ImageLocation.getForDocument(photoSize, document), "80_80",
                svgThumb, gift);
    }
}
