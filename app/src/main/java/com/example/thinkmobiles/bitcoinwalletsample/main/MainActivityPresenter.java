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
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.time.Instant;

/**
 * Created by Lynx on 4/11/2017.
 */

public class MainActivityPresenter implements MainActivityContract.MainActivityPresenter {

    private MainActivityContract.MainActivityView view;
    private File walletDir;
    private NetworkParameters parameters;
    private WalletAppKit walletAppKit;

    private final File walletFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean walletReady = false;

    public MainActivityPresenter(MainActivityContract.MainActivityView view, File walletDir) {
        this.view = view;
        this.walletDir = walletDir;
        this.walletFile = new File(walletDir, Constants.WALLET_NAME + ".wallet");
        view.setPresenter(this);
    }

    @Override
    public void subscribe() {
        setBtcSDKThread();
        parameters = Constants.IS_PRODUCTION ? MainNetParams.get() : TestNet3Params.get();
        BriefLogFormatter.init();

        // ✅ TOÀN BỘ KHỞI TẠO CHẠY TRÊN LUỒNG RIÊNG → KHÔNG VĂNG ANDROID 16
        new Thread(() -> {
            walletAppKit = new WalletAppKit(parameters, walletDir, Constants.WALLET_NAME) {
                @Override
                protected void onSetupCompleted() {
                    if (wallet().getImportedKeys().size() < 1)
                        wallet().importKey(new ECKey());

                    runOnUi(() -> view.displayWalletPath(walletFile.getAbsolutePath()));
                    setupWalletListeners(wallet());
                    walletReady = true;

                    Log.d("myLogs", "My address = " + wallet().freshReceiveAddress());
                    runOnUi(() -> refresh());
                }
            };

            walletAppKit.setDownloadListener(new DownloadProgressTracker() {
                @Override
                protected void progress(double pct, int blocksSoFar, Instant date) {
                    super.progress(pct, blocksSoFar, date);
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
        }).start();
    }

    @Override
    public void unsubscribe() {
        walletReady = false;
        new Thread(() -> {
            try {
                if (walletAppKit != null)
                    walletAppKit.stopAsync().awaitTerminated();
            } catch (Exception ignored) {}
            walletAppKit = null;
        }).start();
    }

    @Override
    public void refresh() {
        if (!walletReady || walletAppKit == null) return;
        new Thread(() -> {
            Wallet w = walletAppKit.wallet();
            String myAddress = w.freshReceiveAddress().toString();
            runOnUi(() -> {
                view.displayMyBalance(w.getBalance().toFriendlyString());
                view.displayMyAddress(myAddress);
            });
        }).start();
    }

    @Override
    public void pickRecipient() {
        view.displayRecipientAddress(null);
        view.startScanQR();
    }

    @Override
    public void send() {
        if (!walletReady || walletAppKit == null) return;

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

        new Thread(() -> {
            Wallet w = walletAppKit.wallet();
            Coin coinAmount = Coin.parseCoin(amount);

            if(w.getBalance().isLessThan(coinAmount)) {
                runOnUi(() -> view.showToastMessage("You got not enough coins"));
                return;
            }

            SendRequest request = SendRequest.to(
                    LegacyAddress.fromBase58(parameters, recipientAddress),
                    coinAmount
            );

            try {
                w.completeTx(request);
                w.commitTx(request.tx);
                walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();
                runOnUi(() -> {
                    view.clearAmount();
                    view.displayRecipientAddress(null);
                });
            } catch (InsufficientMoneyException e) {
                e.printStackTrace();
                runOnUi(() -> view.showToastMessage(e.getMessage()));
            }
        }).start();
    }

    @Override
    public void getInfoDialog() {
        if (!walletReady || walletAppKit == null) return;
        new Thread(() -> {
            String addr = walletAppKit.wallet().currentReceiveAddress().toString();
            runOnUi(() -> view.displayInfoDialog(addr));
        }).start();
    }

    private void setBtcSDKThread() {
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

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }
}
