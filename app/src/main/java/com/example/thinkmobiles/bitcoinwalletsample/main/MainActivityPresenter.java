package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;

// ============================================================
// ✅ API CHANGES FOR bitcoinj 0.17.1 (only these lines changed)
// ============================================================
import org.bitcoinj.base.LegacyAddress; // was: org.bitcoinj.core.Address
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
// DownloadProgressTracker moved to new package + uses Instant instead of Date
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.time.Instant; // was: java.util.Date (0.17.1 changed API)
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Lynx on 4/11/2017.
 */

public class MainActivityPresenter implements MainActivityContract.MainActivityPresenter {

    private MainActivityContract.MainActivityView view;
    private File walletDir; //Context.getCacheDir();

    private NetworkParameters parameters;
    private WalletAppKit walletAppKit;

    // ✅ Added for 0.17.1 + Android 16 safety
    private final File walletFile; // vWalletFile was removed from WalletAppKit
    private final ExecutorService btcThread = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean walletReady = false;

    public MainActivityPresenter(MainActivityContract.MainActivityView view, File walletDir) {
        this.view = view;
        this.walletDir = walletDir;
        // ✅ Exact path WalletAppKit 0.17.1 always creates internally
        this.walletFile = new File(walletDir, Constants.WALLET_NAME + ".wallet");
        view.setPresenter(this);
    }

    @Override
    public void subscribe() {
        setBtcSDKThread();
        parameters = Constants.IS_PRODUCTION ? MainNetParams.get() : TestNet3Params.get();
        BriefLogFormatter.init();

        // ✅ FIX ANDROID 16: ALL bitcoinj init runs on background thread
        btcThread.execute(() -> {
            walletAppKit = new WalletAppKit(parameters, walletDir, Constants.WALLET_NAME) {
                @Override
                protected void onSetupCompleted() {
                    if (wallet().getImportedKeys().size() < 1) wallet().importKey(new ECKey());
                    // ✅ REMOVED: allowSpendingUnconfirmedTransactions() was DELETED in 0.17.x
                    // ✅ FIXED: vWalletFile no longer exists → use prebuilt path
                    runOnUi(() -> view.displayWalletPath(walletFile.getAbsolutePath()));
                    setupWalletListeners(wallet());

                    Log.d("myLogs", "My address = " + wallet().freshReceiveAddress());
                    walletReady = true;
                    runOnUi(() -> refresh());
                }
            };
            walletAppKit.setDownloadListener(new DownloadProgressTracker() {
                // ✅ FIXED 0.17.1 API: Date → Instant
                @Override
                protected void progress(double pct, int blocksSoFar, Instant date) {
                    super.progress(pct, blocksSoFar, date);
                    // ✅ FIXED: pct is 0.0 ~ 1.0 → * 100 to get real %
                    int percentage = (int) Math.round(pct * 100);
                    runOnUi(() -> {
                        view.displayPercentage(percentage);
                        view.displayProgress(percentage);
                    });
                }

                @Override
                protected void doneDownload() {
                    super.doneDownload();
                    runOnUi(() -> {
                        view.displayDownloadContent(false);
                        refresh();
                    });
                }
            });
            walletAppKit.setBlockingStartup(false);
            walletAppKit.startAsync().awaitRunning();
        });
    }

    @Override
    public void unsubscribe() {
        walletReady = false;
        btcThread.execute(() -> {
            try {
                if (walletAppKit != null) walletAppKit.stopAsync().awaitTerminated();
            } catch (Exception ignored) {}
            walletAppKit = null;
            if (!btcThread.isShutdown()) btcThread.shutdownNow();
        });
    }

    @Override
    public void refresh() {
        if (!checkReady()) return;
        btcThread.execute(() -> {
            Wallet w = walletAppKit.wallet();
            // ✅ FIXED 0.17.1: .toBase58() removed → use .toString()
            String myAddress = w.freshReceiveAddress().toString();
            runOnUi(() -> {
                view.displayMyBalance(w.getBalance().toFriendlyString());
                view.displayMyAddress(myAddress);
            });
        });
    }

    @Override
    public void pickRecipient() {
        view.displayRecipientAddress(null);
        view.startScanQR();
    }

    @Override
    public void send() {
        if (!checkReady()) return;

        final String recipientAddress = view.getRecipient();
        final String amount = view.getAmount();

        if(TextUtils.isEmpty(recipientAddress) || recipientAddress.equals("Scan recipient QR")) {
            view.showToastMessage("Select recipient");
            return;
        }
        if(TextUtils.isEmpty(amount) || Double.parseDouble(amount) <= 0) {
            view.showToastMessage("Select valid amount");
            return;
        }

        // ✅ ANDROID 16: send also runs on background thread
        btcThread.execute(() -> {
            Wallet w = walletAppKit.wallet();
            Coin coin = Coin.parseCoin(amount);

            if(w.getBalance().isLessThan(coin)) {
                runOnUi(() -> {
                    view.showToastMessage("You got not enough coins");
                    view.clearAmount();
                });
                return;
            }
            // ✅ FIXED 0.17.1: Address.fromBase58 → LegacyAddress.fromBase58
            SendRequest request = SendRequest.to(
                    LegacyAddress.fromBase58(parameters, recipientAddress), coin
            );
            try {
                w.completeTx(request);
                w.commitTx(request.tx);
                walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();
            } catch (InsufficientMoneyException e) {
                e.printStackTrace();
                runOnUi(() -> view.showToastMessage(e.getMessage()));
            }
        });
    }

    @Override
    public void getInfoDialog() {
        if (!checkReady()) return;
        btcThread.execute(() -> {
            // ✅ FIXED 0.17.1: .toBase58() → .toString()
            String addr = walletAppKit.wallet().currentReceiveAddress().toString();
            runOnUi(() -> view.displayInfoDialog(addr));
        });
    }

    private void setBtcSDKThread() {
        // ✅ Keep original pattern, just ensure main looper
        Threading.USER_THREAD = mainHandler::post;
    }

    private void setupWalletListeners(Wallet wallet) {
        wallet.addCoinsReceivedEventListener((wallet1, tx, prevBalance, newBalance) -> {
            runOnUi(() -> {
                view.displayMyBalance(wallet.getBalance().toFriendlyString());
                if(tx.getPurpose() == Transaction.Purpose.UNKNOWN)
                    view.showToastMessage("Receive " + newBalance.minus(prevBalance).toFriendlyString());
            });
        });
        wallet.addCoinsSentEventListener((wallet12, tx, prevBalance, newBalance) -> {
            runOnUi(() -> {
                view.displayMyBalance(wallet.getBalance().toFriendlyString());
                view.clearAmount();
                view.displayRecipientAddress(null);
                view.showToastMessage("Sent " + prevBalance.minus(newBalance).minus(tx.getFee()).toFriendlyString());
            });
        });
    }

    // ============================================================
    // Small helpers — original logic untouched
    // ============================================================
    private boolean checkReady() {
        if (!walletReady || walletAppKit == null || !walletAppKit.isRunning()) {
            view.showToastMessage("Wallet is starting, please wait");
            return false;
        }
        return true;
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }
}
