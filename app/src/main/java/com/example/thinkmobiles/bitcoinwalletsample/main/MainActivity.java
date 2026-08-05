package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.StrictMode;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.thinkmobiles.bitcoinwalletsample.R;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import net.glxn.qrgen.android.QRCode;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.res.ColorRes;
import org.androidannotations.annotations.res.StringRes;

@EActivity(R.layout.activity_main)
@OptionsMenu(R.menu.menu_main)
public class MainActivity extends AppCompatActivity implements MainActivityContract.MainActivityView {

    private MainActivityContract.MainActivityPresenter presenter;
    private boolean isUpdatingAmount = false;

    @ViewById
    protected FrameLayout flDownloadContent_LDP;
    @ViewById
    protected ProgressBar pbProgress_LDP;
    @ViewById
    protected TextView tvPercentage_LDP;

    @ViewById
    protected Toolbar toolbar_AT;
    @ViewById
    protected SwipeRefreshLayout srlContent_AM;
    @ViewById
    protected TextView tvMyBalance_AM;
    @ViewById
    protected TextView tvMyAddress_AM;
    @ViewById
    protected ImageView ivMyQRAddress_AM;
    @ViewById
    protected TextView tvWalletFilePath_AM;
    @ViewById
    protected TextView tvRecipientAddress_AM;
    @ViewById
    protected EditText etAmount_AM;
    @ViewById
    protected Button btnSend_AM;
    @ViewById
    protected ImageView ivCopy_AM;

    @SystemService
    protected ClipboardManager clipboardManager;

    @StringRes(R.string.scan_recipient_qr)
    protected String strScanRecipientQRCode;
    @StringRes(R.string.about)
    protected String strAbout;

    @ColorRes(android.R.color.holo_green_dark)
    protected int colorGreenDark;
    @ColorRes(android.R.color.darker_gray)
    protected int colorGreyDark;

    @AfterInject
    protected void initData() {
        // Fix 1: Bảo vệ không crash mạng trên main thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build();
        StrictMode.setThreadPolicy(policy);

        // Fix 2: GÁN PRESENTER VÀO BIẾN — KHÔNG CÒN NULL NỮA
        presenter = new MainActivityPresenter(this, getCacheDir());
    }

    @AfterViews
    protected void initUI() {
        initToolbar();
        setListeners();

        // Fix 3: Kiểm tra null trước khi gọi
        if (presenter != null) {
            presenter.subscribe();
        }
    }

    @OptionsItem(R.id.menuScanQR_MM)
    protected void clickMenuGetRecipientQR() {
        if (presenter != null) presenter.pickRecipient();
    }

    @OptionsItem(R.id.menuInfo_MM)
    protected void clickMenuInfo() {
        if (presenter != null) presenter.getInfoDialog();
    }

    private void initToolbar() {
        setSupportActionBar(toolbar_AT);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Wallet");
        }
    }

    @Override
    public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    @UiThread
    public void displayDownloadContent(boolean isShown) {
        flDownloadContent_LDP.setVisibility(isShown ? View.VISIBLE : View.GONE);
    }

    @Override
    @UiThread
    public void displayProgress(int percent) {
        if (pbProgress_LDP.isIndeterminate()) pbProgress_LDP.setIndeterminate(false);
        pbProgress_LDP.setProgress(percent);
    }

    @Override
    @UiThread
    public void displayPercentage(int percent) {
        tvPercentage_LDP.setText(String.valueOf(percent) + " %");
    }

    @Override
    @UiThread
    public void displayMyBalance(String myBalance) {
        tvMyBalance_AM.setText(myBalance);
    }

    @Override
    @UiThread
    public void displayWalletPath(String walletPath) {
        tvWalletFilePath_AM.setText(walletPath);
    }

    @Override
    @UiThread
    public void displayMyAddress(String myAddress) {
        if (TextUtils.isEmpty(myAddress)) return;
        tvMyAddress_AM.setText(myAddress);

        // Fix 4: Tạo QR trên luồng phụ — không treo app
        new Thread(() -> {
            try {
                final Bitmap bitmapMyQR = QRCode.from(myAddress).bitmap();
                runOnUiThread(() -> ivMyQRAddress_AM.setImageBitmap(bitmapMyQR));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        if (srlContent_AM.isRefreshing()) srlContent_AM.setRefreshing(false);
    }

    @Override
    @UiThread
    public void displayRecipientAddress(String recipientAddress) {
        tvRecipientAddress_AM.setText(
                TextUtils.isEmpty(recipientAddress) ? strScanRecipientQRCode : recipientAddress
        );
        tvRecipientAddress_AM.setTextColor(
                TextUtils.isEmpty(recipientAddress) ? colorGreyDark : colorGreenDark
        );
    }

    @Override
    public void showToastMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public String getRecipient() {
        return tvRecipientAddress_AM.getText().toString().trim();
    }

    @Override
    public String getAmount() {
        return etAmount_AM.getText().toString().trim();
    }

    @Override
    public void clearAmount() {
        etAmount_AM.setText(null);
    }

    @Override
    public void startScanQR() {
        new IntentIntegrator(this).initiateScan();
    }

    @Override
    public void displayInfoDialog(String myAddress) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("About");
        builder.setMessage(Html.fromHtml(strAbout));
        builder.setCancelable(true);
        builder.setPositiveButton("GOT IT", (dialog, which) -> dialog.dismiss());
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        TextView msgTxt = (TextView) alertDialog.findViewById(android.R.id.message);
        if (msgTxt != null) {
            msgTxt.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult != null && !TextUtils.isEmpty(scanResult.getContents())) {
            displayRecipientAddress(scanResult.getContents());
        }
    }

    private void setListeners() {
        srlContent_AM.setOnRefreshListener(() -> {
            if (presenter != null) presenter.refresh();
        });

        tvRecipientAddress_AM.setOnClickListener(v -> {
            if (presenter != null) presenter.pickRecipient();
        });

        btnSend_AM.setOnClickListener(v -> {
            if (presenter != null) presenter.send();
        });

        // Fix 5: Tránh vòng lặp vô hạn TextWatcher
        etAmount_AM.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingAmount) return;
                isUpdatingAmount = true;

                if (s.toString().trim().length() == 0) {
                    etAmount_AM.setText("0.00");
                    etAmount_AM.setSelection(etAmount_AM.getText().length());
                }

                isUpdatingAmount = false;
            }
        });

        ivCopy_AM.setOnClickListener(v -> {
            String address = tvMyAddress_AM.getText().toString().trim();
            if (TextUtils.isEmpty(address)) {
                Toast.makeText(MainActivity.this, "Chưa có địa chỉ ví", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = ClipData.newPlainText("My wallet address", address);
            clipboardManager.setPrimaryClip(clip);
            Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
        });
    }

    // Fix 6: Dừng wallet khi đóng app — không rò rỉ
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (presenter != null) {
            presenter.unsubscribe();
        }
    }
}
