package jp.example.shepardkeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BillingManager implements PurchasesUpdatedListener {
    private static final String TAG = "BillingManager";
    private static final String PREF_ADS_REMOVED = "ads_removed";
    private static final String PREFS_NAME = "ShepardPresets";
    
    // Placeholder Product ID for testing
    public static final String PRODUCT_ID_NO_ADS = "permanent_no_ads";

    private final BillingClient billingClient;
    private final Activity activity;
    private final BillingListener listener;
    private boolean isAdsRemoved = false;

    public interface BillingListener {
        void onAdsRemovedStatusChanged(boolean adsRemoved);
        void onBillingError(String message);
    }

    public BillingManager(Activity activity, BillingListener listener) {
        this.activity = activity;
        this.listener = listener;
        
        // Check local cache first
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isAdsRemoved = prefs.getBoolean(PREF_ADS_REMOVED, false);

        billingClient = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .build();
    }

    public boolean isAdsRemoved() {
        return isAdsRemoved;
    }

    public void startConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup finished");
                    queryPurchases();
                } else {
                    Log.e(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected");
            }
        });
    }

    public void queryPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        boolean adsRemovedLocally = false;
                        for (Purchase purchase : purchases) {
                            if (purchase.getProducts().contains(PRODUCT_ID_NO_ADS) &&
                                    purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                adsRemovedLocally = true;
                                break;
                            }
                        }
                        updateAdsRemovedStatus(adsRemovedLocally);
                    }
                }
        );
    }

    public void launchPurchaseFlow() {
        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_NO_ADS)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !productDetailsList.isEmpty()) {
                ProductDetails productDetails = productDetailsList.get(0);
                
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails)
                                        .build()
                        ))
                        .build();
                
                billingClient.launchBillingFlow(activity, flowParams);
            } else {
                String errorMsg = "Product not found. Please ensure the Product ID '" + PRODUCT_ID_NO_ADS + "' is set up in Play Console.";
                Log.e(TAG, errorMsg + " " + billingResult.getDebugMessage());
                listener.onBillingError(errorMsg);
            }
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User canceled the purchase");
        } else {
            Log.e(TAG, "Purchase error: " + billingResult.getDebugMessage());
            listener.onBillingError("Purchase error: " + billingResult.getDebugMessage());
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getProducts().contains(PRODUCT_ID_NO_ADS) &&
                purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams acknowledgePurchaseParams =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                billingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase acknowledged");
                    }
                });
            }
            updateAdsRemovedStatus(true);
        }
    }

    private void updateAdsRemovedStatus(boolean removed) {
        if (isAdsRemoved != removed) {
            isAdsRemoved = removed;
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(PREF_ADS_REMOVED, removed).apply();
            activity.runOnUiThread(() -> listener.onAdsRemovedStatusChanged(removed));
        }
    }
}
