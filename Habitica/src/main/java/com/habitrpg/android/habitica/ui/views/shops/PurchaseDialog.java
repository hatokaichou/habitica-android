package com.habitrpg.android.habitica.ui.views.shops;

import android.app.AlertDialog;
import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.habitrpg.android.habitica.R;
import com.habitrpg.android.habitica.components.AppComponent;
import com.habitrpg.android.habitica.data.InventoryRepository;
import com.habitrpg.android.habitica.data.UserRepository;
import com.habitrpg.android.habitica.events.ShowSnackbarEvent;
import com.habitrpg.android.habitica.events.commands.OpenGemPurchaseFragmentCommand;
import com.habitrpg.android.habitica.helpers.RxErrorHandler;
import com.habitrpg.android.habitica.models.shops.Shop;
import com.habitrpg.android.habitica.models.shops.ShopItem;
import com.habitrpg.android.habitica.models.user.User;
import com.habitrpg.android.habitica.ui.views.CurrencyView;
import com.habitrpg.android.habitica.ui.views.CurrencyViews;
import com.habitrpg.android.habitica.ui.views.HabiticaSnackbar;

import org.greenrobot.eventbus.EventBus;

import java.util.Date;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import rx.Observable;
import rx.subscriptions.CompositeSubscription;

public class PurchaseDialog extends AlertDialog {

    @Inject
    UserRepository userRepository;
    @Inject
    InventoryRepository inventoryRepository;

    @BindView(R.id.currencyView)
    CurrencyViews currencyView;
    @BindView(R.id.limitedTextView)
    TextView limitedTextView;
    @BindView(R.id.priceLabel)
    CurrencyView priceLabel;
    @BindView(R.id.buyButton)
    View buyButton;
    @BindView(R.id.content_container)
    ViewGroup contentContainer;
    @BindView(R.id.scrollView)
    ScrollView scrollView;

    private ShopItem shopItem;

    private CompositeSubscription compositeSubscription;
    public String shopIdentifier;
    private User user;

    public PurchaseDialog(Context context, AppComponent component, ShopItem item) {
        super(context);

        component.inject(this);

        compositeSubscription = new CompositeSubscription();

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_purchase_shopitem, null);
        ButterKnife.bind(this, view);
        setView(view);

        setShopItem(item);

        compositeSubscription.add(userRepository.getUser().subscribe(this::setUser, RxErrorHandler.handleEmptyError()));
    }

    private void setUser(User user) {
        this.user = user;
        currencyView.setGold(user.getStats().getGp());
        currencyView.setGems(user.getGemCount());
        currencyView.setHourglasses(user.getHourglassCount());

        if ("gems".equals(shopItem.purchaseType)) {
            int gemsLeft = shopItem.limitedNumberLeft != null ? shopItem.limitedNumberLeft : 0;
            int maxGems = user.getPurchased().getPlan().totalNumberOfGems();
            if (maxGems > 0) {
                limitedTextView.setText(getContext().getString(R.string.gems_left_max, gemsLeft, maxGems));
            } else {
                limitedTextView.setText(getContext().getString(R.string.gems_left_nomax, gemsLeft));
            }
            limitedTextView.setVisibility(View.VISIBLE);
            limitedTextView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.green_10));
        }

        if (shopItem != null && !shopItem.canBuy(user)) {
            buyButton.setEnabled(false);
            priceLabel.setCantAfford(true);
        }
    }

    @Override
    public void dismiss() {
        userRepository.close();
        inventoryRepository.close();
        if (compositeSubscription != null && !compositeSubscription.isUnsubscribed()) {
            compositeSubscription.unsubscribe();
        }
        super.dismiss();
    }

    private void setShopItem(ShopItem item) {
        this.shopItem = item;

        buyButton.setVisibility(View.VISIBLE);

        if (item.getUnlockCondition() == null) {
            priceLabel.setValue(Double.valueOf(item.getValue()));
            priceLabel.setCurrency(item.getCurrency());
        } else {
            setBuyButtonEnabled(false);
        }

        if (item.isLimited()) {
            //TODO: replace with correct date once API is final
            limitedTextView.setText(getContext().getString(R.string.available_until, new Date().toString()));
        } else {
            limitedTextView.setVisibility(View.GONE);
        }

        priceLabel.setLocked(item.getLocked());

        PurchaseDialogContent contentView;
        if (shopItem.isTypeItem()) {
            contentView = new PurchaseDialogItemContent(getContext());
        } else if (shopItem.isTypeQuest()) {
            contentView = new PurchaseDialogQuestContent(getContext());
            inventoryRepository.getQuestContent(item.getKey()).first().subscribe(((PurchaseDialogQuestContent) contentView)::setQuestContent, RxErrorHandler.handleEmptyError());
        } else if (shopItem.isTypeGear()) {
            contentView = new PurchaseDialogGearContent(getContext());
            inventoryRepository.getEquipment(item.getKey()).first().subscribe(((PurchaseDialogGearContent) contentView)::setEquipment, RxErrorHandler.handleEmptyError());
        } else if ("gems".equals(shopItem.purchaseType)) {
            contentView = new PurchaseDialogGemsContent(getContext());
        } else {
            contentView = new PurchaseDialogBaseContent(getContext());
        }
        contentView.setItem(shopItem);
        contentContainer.addView(contentView);


        setScrollviewSize();
    }

    private void setScrollviewSize() {
        scrollView.post(() -> {
            if (getWindow() != null) {
                int height = scrollView.getChildAt(0).getHeight();
                DisplayMetrics displayMetrics = new DisplayMetrics();
                getWindow().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenHeight = displayMetrics.heightPixels;
                int spaceRequired = (int) (displayMetrics.density * 160);

                if (height > screenHeight-spaceRequired) {
                    ViewGroup.LayoutParams myScrollViewParams = scrollView.getLayoutParams();
                    myScrollViewParams.height = screenHeight-spaceRequired;
                    scrollView.setLayoutParams(myScrollViewParams);

                }
            }
        });

    }


    @OnClick(R.id.closeButton)
    void onCloseClicked() {
        dismiss();
    }

    @OnClick(R.id.buyButton)
    void onBuyButtonClicked() {
        if (shopItem.canBuy(user) || !shopItem.getCurrency().equals("gems")) {
            Observable<Void> observable;
            if ((shopIdentifier!= null && shopIdentifier.equals(Shop.TIME_TRAVELERS_SHOP)) || "mystery_set".equals(shopItem.purchaseType)) {
                if (shopItem.purchaseType.equals("gear")) {
                    observable = inventoryRepository.purchaseMysterySet(shopItem.categoryIdentifier);
                } else {
                    observable = inventoryRepository.purchaseHourglassItem(shopItem.purchaseType, shopItem.key);
                }
            } else if (shopItem.purchaseType.equals("quests") && shopItem.getCurrency().equals("gold")) {
                observable = inventoryRepository.purchaseQuest(shopItem.key);
            } else if ("gold".equals(shopItem.currency)) {
                observable = inventoryRepository.buyItem(user, shopItem.key, shopItem.value).flatMap(buyResponse -> Observable.just(null));
            } else {
                observable = inventoryRepository.purchaseItem(shopItem.purchaseType, shopItem.key);
            }
            observable
                    .doOnNext(aVoid -> {
                        ShowSnackbarEvent event = new ShowSnackbarEvent();
                        event.title = getContext().getString(R.string.successful_purchase, shopItem.text);
                        event.type = HabiticaSnackbar.SnackbarDisplayType.NORMAL;
                        EventBus.getDefault().post(event);
                    })
                    .flatMap(buyResponse -> userRepository.retrieveUser(false, true))
                    .flatMap(user1 -> inventoryRepository.retrieveInAppRewards())
                    .subscribe(buyResponse -> {}, throwable -> {
                        retrofit2.HttpException error = (retrofit2.HttpException) throwable;
                        if (error.code() == 401 && shopItem.getCurrency().equals("gems")) {
                            EventBus.getDefault().post(new OpenGemPurchaseFragmentCommand());
                        }
                    });
        } else {
            EventBus.getDefault().post(new OpenGemPurchaseFragmentCommand());
        }
        dismiss();
    }

    private void setBuyButtonEnabled(boolean enabled) {
        if (enabled) {
            buyButton.setAlpha(0.5f);
        } else{
            buyButton.setAlpha(1.0f);
        }
    }
}
