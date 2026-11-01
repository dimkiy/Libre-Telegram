package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.GiftAuctionController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;
import java.util.Set;

/**
 * Prizma: picks showcase NFTs for Telegram's crafting forge. Telegram's own picker lists gifts from
 * the server, which doesn't know our showcase copies — so this glass sheet lists them instead, and
 * can make one more upgraded copy on the spot.
 */
public final class PrizmaCraftPicker {

    private PrizmaCraftPicker() {}

    public static void show(Context context, View behind, long collectionId, String collectionTitle,
                            Set<Long> exclude, Utilities.Callback<TL_stars.StarGift> onSelect) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(16));

        TextView title = new TextView(context);
        title.setText("Выберите подарок");
        title.setTextColor(PrizmaGlass.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        TextView sub = new TextView(context);
        sub.setText("«" + (collectionTitle == null ? "" : collectionTitle) + "» · каждый подарок добавляет "
                + (PrizmaGifts.CRAFT_CHANCE_PERMILLE / 10) + "% к шансу. Подарки, отданные в кузницу, сгорают.");
        sub.setTextColor(PrizmaGlass.TEXT_2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 16));

        final LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final PrizmaGlass.Sheet[] ref = new PrizmaGlass.Sheet[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            grid.removeAllViews();
            ArrayList<View> cells = new ArrayList<>();
            for (TL_stars.TL_starGiftUnique g : PrizmaGifts.craftCandidates(collectionId, exclude)) {
                cells.add(giftCell(context, g, () -> {
                    if (ref[0] != null) ref[0].dismiss();
                    onSelect.run(g);
                }));
            }
            cells.add(addCopyCell(context, () -> addUpgradedCopy(collectionId, () -> fill[0].run())));
            final int cols = 3;
            LinearLayout row = null;
            for (int i = 0; i < cells.size(); i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                }
                row.addView(cells.get(i), LayoutHelper.createLinear(0, 118, 1f, i % cols == 0 ? 0 : 5, 0, i % cols == cols - 1 ? 0 : 5, 0));
            }
            int rest = cells.size() % cols;
            if (row != null && rest != 0) {
                for (int k = rest; k < cols; k++) {
                    row.addView(new View(context), LayoutHelper.createLinear(0, 118, 1f, 5, 0, k == cols - 1 ? 0 : 5, 0));
                }
            }
        };
        fill[0].run();

        ref[0] = new PrizmaGlass.Sheet(context, behind, root);
        ref[0].show();
    }

    /** One more copy of the collection, upgraded with random (non-crafting) traits. */
    private static void addUpgradedCopy(long collectionId, Runnable done) {
        final TL_stars.StarGift base = PrizmaGifts.find(collectionId);
        if (base == null) return;
        GiftAuctionController.getInstance(UserConfig.selectedAccount).requestAuctionUpgrades(collectionId, attrs -> {
            ArrayList<TL_stars.StarGiftAttribute> pool = attrs != null ? GiftAuctionController.filterAttributes(attrs, false) : null;
            if (!PrizmaGifts.isCompleteSample(pool)) {
                pool = PrizmaGifts.fallbackSampleAttributes(base);
            }
            long inst = PrizmaGifts.addInstance(collectionId);
            if (inst != 0) {
                PrizmaGifts.rollUpgrade(inst, base, pool);
            }
            done.run();
        });
    }

    private static View giftCell(Context context, TL_stars.TL_starGiftUnique g, Runnable onClick) {
        FrameLayout cell = new FrameLayout(context);
        cell.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(18)));

        TL_stars.starGiftAttributeBackdrop bd = StarsController.findAttribute(g.attributes, TL_stars.starGiftAttributeBackdrop.class);
        View disc = new View(context);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(AndroidUtilities.dp(34));
        gd.setColors(bd != null ? new int[]{0xff000000 | bd.center_color, 0xff000000 | bd.edge_color} : new int[]{0xff3a3d46, 0xff24262c});
        disc.setBackground(gd);
        cell.addView(disc, LayoutHelper.createFrame(66, 66, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 12, 0, 0));

        TL_stars.starGiftAttributeModel model = StarsController.findAttribute(g.attributes, TL_stars.starGiftAttributeModel.class);
        BackupImageView sticker = new BackupImageView(context);
        if (model != null && model.document != null) {
            TLRPC.Document doc = model.document;
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, AndroidUtilities.dp(64));
            SvgHelper.SvgDrawable svg = DocumentObject.getSvgThumb(doc, Theme.key_windowBackgroundGray, 0.3f);
            sticker.setImage(ImageLocation.getForDocument(doc), "52_52", ImageLocation.getForDocument(thumb, doc), "52_52", svg, doc);
        }
        cell.addView(sticker, LayoutHelper.createFrame(50, 50, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 20, 0, 0));

        TextView num = new TextView(context);
        num.setText("#" + g.num);
        num.setTextColor(PrizmaGlass.TEXT);
        num.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        num.setTypeface(AndroidUtilities.bold());
        num.setGravity(Gravity.CENTER);
        num.setSingleLine(true);
        cell.addView(num, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 6, 0, 6, 12));

        TextView chance = new TextView(context);
        chance.setText("+" + (g.craft_chance_permille / 10) + "%");
        chance.setTextColor(PrizmaGlass.TEXT);
        chance.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        chance.setTypeface(AndroidUtilities.bold());
        chance.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(1), AndroidUtilities.dp(6), AndroidUtilities.dp(1));
        chance.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(9)));
        cell.addView(chance, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));

        cell.setOnClickListener(v -> onClick.run());
        PrizmaGlass.pressable(cell);
        return cell;
    }

    private static View addCopyCell(Context context, Runnable onClick) {
        FrameLayout cell = new FrameLayout(context);
        cell.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(18)));
        ImageView plus = new ImageView(context);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(0xffffffff);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        cell.addView(plus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 66, Gravity.TOP, 0, 12, 0, 0));
        TextView label = new TextView(context);
        label.setText("Добавить\nкопию");
        label.setTextColor(PrizmaGlass.TEXT_2);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 6, 0, 6, 10));
        cell.setOnClickListener(v -> onClick.run());
        PrizmaGlass.pressable(cell);
        return cell;
    }
}
