package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;

import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivityPresenter implements MainActivityContract.MainActivityPresenter {

    private static final String TAG = "BTC_PRESENTER";

    private final MainActivityContract.MainActivityView view;
    private final File walletDir;
    private NetworkParameters parameters;
    private WalletAppKit walletAppKit;

    // ✅ FIX 1: CHẠY TOÀN BỘ BITCOINJ TRÊN LUỒNG NỀN RIÊNG → ANDROID 16 KHÔNG GIẾT
    private final ExecutorService btcExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isWalletReady = new AtomicBoolean(false);

    public MainActivityPresenter(MainActivityContract.MainActivityView view, File walletDir) {
        this.view = view;
        this.walletDir = walletDir;
        view.setPresenter(this);
    }

    @Override
    public void subscribe() {
        setBtcSDKThread();
        parameters = Constants.IS_PRODUCTION ? MainNetParams.get() : TestNet3Params.get();
        BriefLogFormatter.init();

        // ✅ FIX 2: TẤT CẢ KHỞI TẠO VÍ CHẠY LUỒNG NỀN → KHÔNG VĂNG MẠNG MAIN THREAD
        btcExecutor.execute(() -> {
            try {
                Log.d(TAG, "Bắt đầu khởi tạo ví BitcoinJ...");

                walletAppKit = new WalletAppKit(parameters, walletDir, Constants.WALLET_NAME) {
                    @Override
                    protected void onSetupCompleted() {
                        super.onSetupCompleted();
                        try {
                            if (wallet().getImportedKeys().size() < 1) {
                                wallet().importKey(new ECKey());
                            }

                            // ✅ FIX 3: SỬA LỖI vWalletFile KHÔNG ĐỊNH NGHĨA
                            File walletFile = wallet().getWalletFile();
                            if (walletFile != null) {
                                runOnUi(() -> view.displayWalletPath(walletFile.getAbsolutePath()));
                            }

                            setupWalletListeners(wallet());
                            isWalletReady.set(true);

                            String myAddr = wallet().freshReceiveAddress().toString();
                            Log.d(TAG, "Địa chỉ ví mới: " + myAddr);

                            runOnUi(() -> {
                                view.displayMyBalance(wallet().getBalance().toFriendlyString());
                                view.displayMyAddress(myAddr);
                            });

                        } catch (Exception e) {
                            Log.e(TAG, "Lỗi onSetupCompleted", e);
                            showToastSafe("Lỗi khởi tạo ví: " + e.getMessage());
                        }
                    }
                };

                // ✅ FIX 4: TÍNH % DOWNLOAD ĐÚNG (pct * 100)
                walletAppKit.setDownloadListener(new org.bitcoinj.core.listeners.DownloadProgressTracker() {
                    @Override
                    protected void progress(double pct, int blocksSoFar, Instant date) {
                        super.progress(pct, blocksSoFar, date);
                        int percent = (int) Math.round(pct * 100);
                        runOnUi(() -> {
                            view.displayProgress(percent);
                            view.displayPercentage(percent);
                        });
                    }

                    @Override
                    protected void doneDownload() {
                        super.doneDownload();
                        Log.d(TAG, "Sync blockchain xong!");
                        runOnUi(() -> {
                            view.displayDownloadContent(false);
                            refresh();
                        });
                    }
                });

                walletAppKit.setBlockingStartup(false);
                // ✅ KHỞI ĐỘNG VÍ TRÊN LUỒNG NỀN
                walletAppKit.startAsync().awaitRunning();
                Log.d(TAG, "WalletAppKit đã chạy");

            } catch (Exception e) {
                Log.e(TAG, "LỖI KHỞI TẠO VÍ", e);
                isWalletReady.set(false);
                showToastSafe("Không thể khởi tạo ví: " + e.getMessage());
                runOnUi(() -> view.displayDownloadContent(false));
            }
        });
    }

    // ✅ FIX 5: DỪNG VÍ ĐÚNG CÁCH → KHÔNG RÒ RỬI, KHÔNG CRASH KHI ĐÓNG APP
    @Override
    public void unsubscribe() {
        isWalletReady.set(false);
        btcExecutor.execute(() -> {
            try {
                if (walletAppKit != null) {
                    walletAppKit.stopAsync().awaitTerminated();
                    Log.d(TAG, "Đã dừng WalletAppKit");
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi dừng ví", e);
            } finally {
                walletAppKit = null;
                if (!btcExecutor.isShutdown()) {
                    btcExecutor.shutdownNow();
                }
            }
        });
    }

    @Override
    public void refresh() {
        if (!checkWalletReady()) return;
        btcExecutor.execute(() -> {
            try {
                Wallet w = walletAppKit.wallet();
                String myAddress = w.freshReceiveAddress().toString();
                String balance = w.getBalance().toFriendlyString();
                runOnUi(() -> {
                    view.displayMyBalance(balance);
                    view.displayMyAddress(myAddress);
                });
            } catch (Exception e) {
                Log.e(TAG, "Lỗi refresh", e);
                showToastSafe("Lỗi làm mới dữ liệu");
            }
        });
    }

    @Override
    public void pickRecipient() {
        view.displayRecipientAddress(null);
        view.startScanQR();
    }

    @Override
    public void send() {
        if (!checkWalletReady()) return;

        final String recipientAddress = view.getRecipient();
        final String amountStr = view.getAmount();

        // Validate input trên main thread trước
        if (TextUtils.isEmpty(recipientAddress) || recipientAddress.equalsIgnoreCase("Scan recipient QR")) {
            showToastSafe("Vui lòng chọn địa chỉ người nhận");
            return;
        }
        if (TextUtils.isEmpty(amountStr)) {
            showToastSafe("Vui lòng nhập số BTC");
            return;
        }

        double amountVal;
        try {
            amountVal = Double.parseDouble(amountStr);
            if (amountVal <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showToastSafe("Số BTC không hợp lệ");
            return;
        }

        // ✅ FIX 6: TOÀN BỘ GIAO DỊCH CHẠY LUỒNG NỀN → KHÔNG VĂNG ANDROID 16
        btcExecutor.execute(() -> {
            try {
                Wallet w = walletAppKit.wallet();
                Coin amount = Coin.parseCoin(amountStr);

                if (w.getBalance().isLessThan(amount)) {
                    showToastSafe("Số dư không đủ");
                    runOnUi(view::clearAmount);
                    return;
                }

                SendRequest request = SendRequest.to(
                        LegacyAddress.fromBase58(parameters, recipientAddress),
                        amount
                );
                request.feePerKb = Coin.valueOf(1000); // phí mặc định an toàn

                w.completeTx(request);
                w.commitTx(request.tx);
                walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();

                Log.d(TAG, "Giao dịch đã broadcast: " + request.tx.getTxId());
                showToastSafe("✅ Đã gửi BTC thành công!");

                runOnUi(() -> {
                    view.clearAmount();
                    view.displayRecipientAddress(null);
                    view.displayMyBalance(w.getBalance().toFriendlyString());
                });

            } catch (InsufficientMoneyException e) {
                Log.e(TAG, "Không đủ tiền phí", e);
                showToastSafe("Không đủ BTC để trả phí giao dịch");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Địa chỉ không hợp lệ", e);
                showToastSafe("Địa chỉ BTC người nhận không hợp lệ");
            } catch (Exception e) {
                Log.e(TAG, "LỖI GỬI BTC", e);
                showToastSafe("Giao dịch thất bại: " + e.getMessage());
            }
        });
    }

    @Override
    public void getInfoDialog() {
        if (!checkWalletReady()) return;
        btcExecutor.execute(() -> {
            try {
                String addr = walletAppKit.wallet().currentReceiveAddress().toString();
                runOnUi(() -> view.displayInfoDialog(addr));
            } catch (Exception e) {
                showToastSafe("Chưa có địa chỉ ví");
            }
        });
    }

    private void setBtcSDKThread() {
        // ✅ TẤT CẢ CALLBACK BITCOINJ SẼ CHẠY TRÊN MAIN THREAD ĐỂ CẬP NHẬT UI AN TOÀN
        Threading.USER_THREAD = mainHandler::post;
    }

    private void setupWalletListeners(Wallet wallet) {
        wallet.addCoinsReceivedEventListener((w, tx, prevBalance, newBalance) -> {
            Coin received = newBalance.minus(prevBalance);
            Log.d(TAG, "NHẬN ĐƯỢC: " + received.toFriendlyString() + " | TX: " + tx.getTxId());
            runOnUi(() -> {
                view.displayMyBalance(w.getBalance().toFriendlyString());
                if (tx.getPurpose() == Transaction.Purpose.UNKNOWN) {
                    view.showToastMessage("💰 Nhận được " + received.toFriendlyString());
                }
            });
        });

        wallet.addCoinsSentEventListener((w, tx, prevBalance, newBalance) -> {
            Coin sent = prevBalance.minus(newBalance);
            Coin fee = tx.getFee() != null ? tx.getFee() : Coin.ZERO;
            Log.d(TAG, "ĐÃ GỬI: " + sent.toFriendlyString() + " | Phí: " + fee.toFriendlyString());
            runOnUi(() -> {
                view.displayMyBalance(w.getBalance().toFriendlyString());
                view.clearAmount();
                view.displayRecipientAddress(null);
                view.showToastMessage("📤 Đã gửi " + sent.minus(fee).toFriendlyString());
            });
        });
    }

    // ==============================================
    // ✅ CÁC HÀM HỖ TRỢ AN TOÀN
    // ==============================================
    private boolean checkWalletReady() {
        if (!isWalletReady.get() || walletAppKit == null || !walletAppKit.isRunning()) {
            showToastSafe("Ví đang khởi tạo, vui lòng đợi...");
            return false;
        }
        return true;
    }

    private void runOnUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private void showToastSafe(String msg) {
        runOnUi(() -> view.showToastMessage(msg));
    }
}
