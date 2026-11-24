package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.ColdAccounts;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Mr qik yan: cold-account manager ("حساباتي").
 * Lists every stored account; tapping a stored one swaps it into live slot 0
 * and restarts the app into it. The currently live account is archived first,
 * automatically, by the boot-time swap in ColdAccounts.
 */
public class ColdAccountsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        refreshLiveEntry();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(23, 33, 43));

        TextView title = new TextView(this);
        title.setText("حساباتي");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 48, 0, 8);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("الحساب المفتوح فقط يستقبل الرسائل. بقية الحسابات محفوظة نائمة، واضغط أيّاً منها للتبديل إليه.");
        note.setTextColor(Color.rgb(130, 145, 160));
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        note.setPadding(32, 0, 32, 24);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 8, 24, 8);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        final long liveId = ColdAccounts.loadLiveId(this);
        ArrayList<ColdAccounts.Entry> entries = ColdAccounts.loadEntries(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد حسابات محفوظة بعد.\nسجّل الدخول بحسابك أولاً، ثم عُد إلى هنا لإضافته وحفظه.");
            empty.setTextColor(Color.rgb(200, 210, 220));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 40, 0, 40);
            list.addView(empty);
        }
        for (final ColdAccounts.Entry e : entries) {
            final boolean isLive = e.id == liveId;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(28, 24, 28, 24);
            row.setBackgroundColor(isLive ? Color.rgb(38, 58, 78) : Color.rgb(30, 43, 56));

            TextView name = new TextView(this);
            String display = e.name != null && !e.name.isEmpty() ? e.name : (e.username != null && !e.username.isEmpty() ? "@" + e.username : String.valueOf(e.id));
            name.setText((isLive ? "● " : "") + display);
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);

            TextView sub = new TextView(this);
            StringBuilder sb = new StringBuilder();
            if (e.phone != null && !e.phone.isEmpty()) {
                sb.append("+").append(e.phone);
            }
            if (e.username != null && !e.username.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("  ·  ");
                }
                sb.append("@").append(e.username);
            }
            sb.append(isLive ? "  ·  يعمل الآن" : "  ·  محفوظ — اضغط للتبديل");
            sub.setText(sb.toString());
            sub.setTextColor(Color.rgb(130, 145, 160));
            sub.setTextSize(13);
            row.addView(sub);

            if (!isLive) {
                row.setOnClickListener(v -> new AlertDialog.Builder(ColdAccountsActivity.this)
                        .setTitle("التبديل إلى هذا الحساب؟")
                        .setMessage("سيُحفظ الحساب الحالي تلقائياً، ويُعاد تشغيل التطبيق داخل الحساب المختار.")
                        .setPositiveButton("تبديل", (d, w) -> ColdAccounts.requestSwitch(ColdAccountsActivity.this, e.id))
                        .setNegativeButton("إلغاء", null)
                        .show());
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(ColdAccountsActivity.this)
                            .setTitle("حذف النسخة المحفوظة؟")
                            .setMessage("ستُحذف جلسة هذا الحساب المحفوظة من الجهاز نهائياً، وستحتاج إلى تسجيل الدخول من جديد لاستعادته.")
                            .setPositiveButton("حذف", (d, w) -> {
                                ColdAccounts.deleteCold(ColdAccountsActivity.this, e.id);
                                recreate();
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();
                    return true;
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 12;
            list.addView(row, lp);
        }

        Button addButton = new Button(this);
        addButton.setText("＋ إضافة حساب جديد");
        addButton.setTextSize(16);
        addButton.setOnClickListener(v -> {
            if (liveId != 0) {
                new AlertDialog.Builder(ColdAccountsActivity.this)
                        .setTitle("إضافة حساب جديد؟")
                        .setMessage("سيُحفظ الحساب الحالي في القائمة، ويُعاد تشغيل التطبيق إلى شاشة تسجيل الدخول.")
                        .setPositiveButton("متابعة", (d, w) -> ColdAccounts.requestArchiveOnly(ColdAccountsActivity.this))
                        .setNegativeButton("إلغاء", null)
                        .show();
            } else {
                Intent intent = new Intent(ColdAccountsActivity.this, LaunchActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(24, 8, 24, 32);
        root.addView(addButton, blp);

        setContentView(root);
    }

    private void refreshLiveEntry() {
        try {
            UserConfig uc = UserConfig.getInstance(0);
            if (uc != null && uc.isClientActivated()) {
                TLRPC.User u = uc.getCurrentUser();
                if (u != null) {
                    ColdAccounts.Entry e = new ColdAccounts.Entry();
                    e.id = u.id;
                    String first = u.first_name == null ? "" : u.first_name;
                    String last = u.last_name == null ? "" : u.last_name;
                    e.name = (first + " " + last).trim();
                    e.phone = u.phone;
                    e.username = u.username;
                    ColdAccounts.upsertLive(this, e);
                }
            }
        } catch (Throwable ignore) {
        }
    }
}
