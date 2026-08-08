package com.goda.mypic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private DrawerLayout drawerLayout;
    private RelativeLayout layoutTopBar;
    private RecyclerView recyclerView;
    private MediaAdapter adapter;

    private List<MediaItem> allMediaList = new ArrayList<>();
    private List<MediaItem> cameraList = new ArrayList<>();
    private List<MediaItem> screenshotList = new ArrayList<>();
    private List<MediaItem> videoList = new ArrayList<>();
    private List<MediaItem> currentDisplayList = new ArrayList<>();

    private LinearLayout layoutHomeCategories;
    private CardView cardAll, cardCamera, cardScreenshots, cardVideos;
    private ImageView ivCoverAll, ivCoverCamera, ivCoverScreenshots, ivCoverVideos;
    private TextView tvCountAll, tvCountCamera, tvCountScreenshots, tvCountVideos;
    private boolean isViewingCategoryGrid = false;
    private String currentCategoryTitle = "我的相册";

    private LinearLayout layoutSimilarContainer;
    private RecyclerView recyclerSimilarGroups;
    private SimilarGroupAdapter groupAdapter;
    private List<SimilarGroup> similarGroupsResult = new ArrayList<>();
    private boolean isViewingSimilar = false;

    private LinearLayout layoutSearchContainer;
    private TextView tvSearchHeader;
    private RecyclerView recyclerSearchResults;
    private MediaAdapter searchAdapter;
    private List<MediaItem> searchResultList = new ArrayList<>();
    private boolean isViewingSearch = false;
    private EditText etOcrSearch;

    private TextView tvTitle, tvLeftAction, tvRightAction;
    private LinearLayout layoutBottomBar;
//    private Button btnShare, btnCopy, btnMove, btnDelete, btnMenuSimilar, btnMenuSearch, btnMenuDateSearch;
//    private Button btnShare, btnCopy, btnMove, btnDelete, btnMenuSimilar, btnMenuSearch, btnMenuDateSearch, btnMenuGif; // 🚨 加了 btnMenuGif
    private Button btnShare, btnCopy, btnMove, btnDelete, btnMenuSimilar, btnMenuSearch, btnMenuDateSearch, btnMenuGif, btnMenuNoText;
    private FrameLayout layoutViewer;
    private ViewPager2 viewPager;
    private ViewerAdapter viewerAdapter;

    private boolean isViewingSingle = false;
    private boolean isDragSelecting = false;
    private int lastSelectedPosition = -1;
    private boolean isImmersiveMode = false;

    private final List<MediaItem> pendingRemoveItems = new ArrayList<>();
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> safLauncher;
    private Queue<String> pendingAuthQueue = new LinkedList<>();
    private Runnable onAuthCompleteTask;

    // 🚨 将大容器和内部可见滑块分开声明
    private FrameLayout globalFastScroller;
    private View globalFastScrollerThumb;
    private RecyclerView currentFastScrollTarget;
    private Runnable hideScrollerRunnable;

    // 🚨 新增：用于判断是否为刚刚打开 App
    private boolean isFirstLaunch = true;

    // 🚨 新增：全局操作冷却锁，防止 MediaStore 幽灵数据复活
    public static long lastLocalActionTime = 0;

    // 删除确认页会触发 Activity 的 pause/resume。用稳定快照 + 扫描代次彻底隔离旧扫描结果。
    private final List<MediaItem> pendingSystemDeleteItems = new ArrayList<>();
    private final AtomicInteger mediaScanGeneration = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Long> recentlyRemovedUris = new ConcurrentHashMap<>();
    private static final long MEDIASTORE_DELETE_GRACE_MS = 12_000L;

    // 防止 onResume/手动刷新反复启动多条 7000 张规模的 OCR 全量索引任务。
    private final AtomicBoolean ocrIndexingRunning = new AtomicBoolean(false);
    private volatile boolean userOcrInProgress = false;
    private volatile boolean galleryScrollBusy = false;

    // 外部文件管理器 ACTION_VIEW 打开的单张图片使用独立列表，避免被后台相册扫描覆盖。
    private final List<MediaItem> externalViewerList = new ArrayList<>();
    private boolean isViewingExternalImage = false;
    private boolean openedFromExternalIntent = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("MyPicPrefs", MODE_PRIVATE);

        safLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    String currentAuthFolder = prefs.getString("current_auth_target", "");
                    if (!currentAuthFolder.isEmpty()) prefs.edit().putString("saf_uri_" + currentAuthFolder, treeUri.toString()).apply();
                }
            } else { pendingAuthQueue.clear(); }
            processNextAuth();
        });

        initViews(); initRecyclerViews(); initListeners();

        // 从系统文件管理器/第三方应用“用 MyPic 打开”时，先直接展示传入图片，
        // 不让首次权限申请挡在图片前面。已有相册读取权限时仍在后台刷新图库。
        boolean handledExternalImage = handleIncomingImageIntent(getIntent());
        if (handledExternalImage) {
            if (hasLibraryReadPermission()) startScanning();
        } else {
            checkPermissionsAndScan();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        layoutTopBar = findViewById(R.id.layoutTopBar);
        tvTitle = findViewById(R.id.tvTitle);
        tvLeftAction = findViewById(R.id.tvLeftAction);
        tvRightAction = findViewById(R.id.tvRightAction);
        btnMenuSearch = findViewById(R.id.btnMenuSearch);
        btnMenuDateSearch = findViewById(R.id.btnMenuDateSearch);
        layoutBottomBar = findViewById(R.id.layoutBottomBar);
        btnShare = findViewById(R.id.btnShare); btnCopy = findViewById(R.id.btnCopy);
        btnMove = findViewById(R.id.btnMove); btnDelete = findViewById(R.id.btnDelete);
        btnMenuSimilar = findViewById(R.id.btnMenuSimilar);
        etOcrSearch = findViewById(R.id.etOcrSearch);
        recyclerView = findViewById(R.id.recyclerView); layoutViewer = findViewById(R.id.layoutViewer);
        viewPager = findViewById(R.id.viewPager); layoutSimilarContainer = findViewById(R.id.layoutSimilarContainer);
        recyclerSimilarGroups = findViewById(R.id.recyclerSimilarGroups); layoutSearchContainer = findViewById(R.id.layoutSearchContainer);
        tvSearchHeader = findViewById(R.id.tvSearchHeader); recyclerSearchResults = findViewById(R.id.recyclerSearchResults);

        layoutHomeCategories = findViewById(R.id.layoutHomeCategories);
        cardAll = findViewById(R.id.cardAll); cardCamera = findViewById(R.id.cardCamera);
        cardScreenshots = findViewById(R.id.cardScreenshots); cardVideos = findViewById(R.id.cardVideos);
        ivCoverAll = findViewById(R.id.ivCoverAll); ivCoverCamera = findViewById(R.id.ivCoverCamera);
        ivCoverScreenshots = findViewById(R.id.ivCoverScreenshots); ivCoverVideos = findViewById(R.id.ivCoverVideos);
        tvCountAll = findViewById(R.id.tvCountAll); tvCountCamera = findViewById(R.id.tvCountCamera);
        tvCountScreenshots = findViewById(R.id.tvCountScreenshots); tvCountVideos = findViewById(R.id.tvCountVideos);

        btnMenuSearch.setText("OCR 智能搜图"); btnMenuSearch.setBackgroundColor(Color.parseColor("#4CAF50")); btnMenuSearch.setTextColor(Color.WHITE);

        btnMenuDateSearch = findViewById(R.id.btnMenuDateSearch);
        btnMenuGif = findViewById(R.id.btnMenuGif); // 🚨 新增绑定

//        btnMenuGif = findViewById(R.id.btnMenuGif);
        btnMenuNoText = findViewById(R.id.btnMenuNoText); // 🚨 新增绑定

        // 🚨 绑定两层 View
        globalFastScroller = findViewById(R.id.globalFastScroller);
        globalFastScrollerThumb = findViewById(R.id.globalFastScrollerThumb);

        hideScrollerRunnable = () -> {
            globalFastScroller.animate().alpha(0f).setDuration(300).withEndAction(() ->
                    globalFastScroller.setVisibility(View.INVISIBLE)
            ).start();
        };
    }

    private void setupCustomFastScroller() {
        RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                galleryScrollBusy = newState != RecyclerView.SCROLL_STATE_IDLE;
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy == 0) return;

                currentFastScrollTarget = rv;

                // 瞬间打断所有隐藏动画，满血显示
                globalFastScroller.animate().cancel();
                globalFastScroller.removeCallbacks(hideScrollerRunnable);
                globalFastScroller.setAlpha(1f);
                globalFastScroller.setVisibility(View.VISIBLE);

                int offset = rv.computeVerticalScrollOffset();
                int range = rv.computeVerticalScrollRange() - rv.getHeight();
                if (range > 0) {
                    float progress = (float) offset / range;
                    int thumbHeight = globalFastScroller.getHeight() > 0 ? globalFastScroller.getHeight() : (int) (80 * getResources().getDisplayMetrics().density);
                    int maxThumbY = rv.getHeight() - thumbHeight;
                    float finalY = rv.getTop() + (progress * maxThumbY);
                    globalFastScroller.setTranslationY(finalY);
                }

                globalFastScroller.postDelayed(hideScrollerRunnable, 1500);
            }
        };

        recyclerView.addOnScrollListener(scrollListener);
        recyclerSearchResults.addOnScrollListener(scrollListener);

        globalFastScroller.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private float downTranslationY;
            private int lastDispatchedPosition = RecyclerView.NO_POSITION;
            private long lastDispatchMs = 0L;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (currentFastScrollTarget == null) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.removeCallbacks(hideScrollerRunnable);
                        // 🚨 核心：按压状态赋予内部的可见滑块，让它变蓝！
                        globalFastScrollerThumb.setPressed(true);
                        galleryScrollBusy = true;
                        setFastScrollThumbnailMode(currentFastScrollTarget, true);
                        downY = event.getRawY();
                        downTranslationY = v.getTranslationY();
                        lastDispatchedPosition = RecyclerView.NO_POSITION;
                        lastDispatchMs = 0L;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaY = event.getRawY() - downY;
                        float newY = downTranslationY + deltaY;

                        int maxThumbY = currentFastScrollTarget.getHeight() - v.getHeight();
                        float minY = currentFastScrollTarget.getTop();
                        float maxY = minY + maxThumbY;
                        newY = Math.max(minY, Math.min(newY, maxY));
                        v.setTranslationY(newY);

                        if (maxThumbY > 0 && currentFastScrollTarget.getAdapter() != null) {
                            float progress = (newY - minY) / maxThumbY;
                            int totalItems = currentFastScrollTarget.getAdapter().getItemCount();
                            if (totalItems > 0) {
                                int targetPos = Math.max(0, Math.min(totalItems - 1, (int) (progress * (totalItems - 1))));
                                long now = android.os.SystemClock.uptimeMillis();

                                // 手指 MOVE 事件可能达到 100+ 次/秒。限制列表真正跳转到约 20fps，
                                // 避免每经过几十张就创建一批马上被取消的 Glide 解码任务。
                                if (targetPos != lastDispatchedPosition && now - lastDispatchMs >= 50L) {
                                    RecyclerView.LayoutManager layoutManager = currentFastScrollTarget.getLayoutManager();
                                    if (layoutManager instanceof GridLayoutManager) {
                                        ((GridLayoutManager) layoutManager).scrollToPositionWithOffset(targetPos, 0);
                                        lastDispatchedPosition = targetPos;
                                        lastDispatchMs = now;
                                    }
                                }
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 松手时强制落到滑块最终位置，确保没有被 50ms 节流跳过。
                        int finalMaxThumbY = currentFastScrollTarget.getHeight() - v.getHeight();
                        if (finalMaxThumbY > 0 && currentFastScrollTarget.getAdapter() != null) {
                            float finalMinY = currentFastScrollTarget.getTop();
                            float finalProgress = (v.getTranslationY() - finalMinY) / finalMaxThumbY;
                            finalProgress = Math.max(0f, Math.min(1f, finalProgress));
                            int totalItems = currentFastScrollTarget.getAdapter().getItemCount();
                            if (totalItems > 0) {
                                int targetPos = (int) (finalProgress * (totalItems - 1));
                                RecyclerView.LayoutManager layoutManager = currentFastScrollTarget.getLayoutManager();
                                if (layoutManager instanceof GridLayoutManager) {
                                    ((GridLayoutManager) layoutManager).scrollToPositionWithOffset(targetPos, 0);
                                }
                            }
                        }
                        setFastScrollThumbnailMode(currentFastScrollTarget, false);
                        galleryScrollBusy = false;
                        globalFastScrollerThumb.setPressed(false);
                        v.postDelayed(hideScrollerRunnable, 1500);
                        return true;
                }
                return false;
            }
        });
    }

    private void initRecyclerViews() {
        GridLayoutManager mainGridLayout = new GridLayoutManager(this, 4);
        mainGridLayout.setInitialPrefetchItemCount(16);
        recyclerView.setLayoutManager(mainGridLayout);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(24);
        recyclerView.setItemAnimator(null);
        adapter = new MediaAdapter(this, currentDisplayList, new MediaAdapter.OnMediaInteractionListener() {
            @Override public void onSingleClick(MediaItem item) { enterSingleView(item); }
            @Override public void onStartDragSelect(int pos) { isDragSelecting = true; lastSelectedPosition = pos; updateGlobalSelectionUI(); }
            @Override public void onSelectionChanged(int count) { updateGlobalSelectionUI(); }
        });
        recyclerView.setAdapter(adapter);

        recyclerSimilarGroups.setLayoutManager(new GridLayoutManager(this, 1));
        groupAdapter = new SimilarGroupAdapter(); recyclerSimilarGroups.setAdapter(groupAdapter);

        GridLayoutManager searchGridLayout = new GridLayoutManager(this, 4);
        searchGridLayout.setInitialPrefetchItemCount(16);
        recyclerSearchResults.setLayoutManager(searchGridLayout);
        recyclerSearchResults.setHasFixedSize(true);
        recyclerSearchResults.setItemViewCacheSize(24);
        recyclerSearchResults.setItemAnimator(null);
        searchAdapter = new MediaAdapter(this, searchResultList, new MediaAdapter.OnMediaInteractionListener() {
            @Override public void onSingleClick(MediaItem item) { enterSingleView(item); }
            @Override public void onStartDragSelect(int pos) { isDragSelecting = true; lastSelectedPosition = pos; updateGlobalSelectionUI(); }
            @Override public void onSelectionChanged(int count) { updateGlobalSelectionUI(); }
        });
        searchAdapter.selectedItems = adapter.selectedItems; recyclerSearchResults.setAdapter(searchAdapter);

        RecyclerView.OnItemTouchListener dragListener = new RecyclerView.OnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_UP) isDragSelecting = false;
                if (adapter.isMultiSelectMode && isDragSelecting && e.getActionMasked() == MotionEvent.ACTION_MOVE) { handleDragSelect(rv, e, isViewingSearch ? searchResultList : currentDisplayList); return true; }
                return false;
            }
            @Override public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) { if (isDragSelecting && e.getAction() == MotionEvent.ACTION_MOVE) handleDragSelect(rv, e, isViewingSearch ? searchResultList : currentDisplayList); }
            @Override public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        };
        recyclerView.addOnItemTouchListener(dragListener); recyclerSearchResults.addOnItemTouchListener(dragListener);

        setupCustomFastScroller();
    }

    private void initListeners() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
                else if (isViewingSingle) closeViewer();
                else if (adapter.isMultiSelectMode) exitMultiSelectMode();
                else if (isViewingSimilar || isViewingSearch) exitSpecialMode();
                else if (isViewingCategoryGrid) exitCategoryGrid();
                else new AlertDialog.Builder(MainActivity.this).setTitle("退出").setMessage("退出相册？").setPositiveButton("退出", (d, w) -> finish()).setNegativeButton("取消", null).show();
            }
        });

        tvLeftAction.setOnClickListener(v -> {
            if (isViewingSingle) closeViewer();
            else if (adapter.isMultiSelectMode) exitMultiSelectMode();
            else if (isViewingSimilar || isViewingSearch) exitSpecialMode();
            else if (isViewingCategoryGrid) exitCategoryGrid();
            else if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
        });

        tvRightAction.setOnClickListener(v -> {
            if (adapter.isMultiSelectMode) {
                if (isViewingSearch) searchAdapter.selectAll(adapter.selectedItems.size() < searchResultList.size());
                else adapter.selectAll(adapter.selectedItems.size() < currentDisplayList.size());
                updateGlobalSelectionUI();
            }
        });

        btnShare.setOnClickListener(v -> shareSelectedMedia());
        btnCopy.setOnClickListener(v -> {
            if (isViewingSingle) copyCurrentImageText();
            else requestBatchSafAuth(() -> showSafTransferDialog(false));
        });
        btnMove.setOnClickListener(v -> requestBatchSafAuth(() -> showSafTransferDialog(true)));
        btnDelete.setOnClickListener(v -> requestBatchSafAuth(this::performSafTrashTask));
        btnMenuSimilar.setOnClickListener(v -> { if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START); requestBatchSafAuth(this::startSimilarScanTask); });

        btnMenuSearch.setOnClickListener(v -> {
            String keyword = etOcrSearch.getText().toString().trim();
            if (keyword.isEmpty()) { Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show(); return; }
            hideKeyboard(v);
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            startDatabaseSearchTask(keyword);
        });

        btnMenuDateSearch.setOnClickListener(v -> {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            showDatePickerDialog();
        });

        btnMenuGif.setOnClickListener(v -> {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            startGifSearchTask(); // 🚨 调用专属的 GIF 扫描任务
        });

        btnMenuNoText.setOnClickListener(v -> {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            startNoTextSearchTask(); // 🚨 调用专属的无字图过滤任务
        });
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    // ================= 🚨 核心修复 1：重构首页分类算法，确保相机黑名单 100% 满血 =================
    private void categorizeMedia() {
        cameraList.clear(); screenshotList.clear(); videoList.clear();
        for (MediaItem item : allMediaList) {
            if (item.type == MediaItem.MediaType.VIDEO) {
                videoList.add(item);
            } else {
                if (item.path == null) continue;
                String lowerPath = item.path.toLowerCase();

                // 1. 先抓截图
                if (lowerPath.contains("screenshot") || lowerPath.contains("截屏")) {
                    screenshotList.add(item);
                }
                // 2. 🚨 放宽到极致的相机特征：
                // 只要路径里包含 camera、或者是各家厂商默认的 dcim/100, dcim/101, 或者是苹果传过来的 apple100
                else if (lowerPath.contains("camera") || lowerPath.contains("dcim/10") || lowerPath.contains("apple10")) {
                    cameraList.add(item);
                }
            }
        }
    }

    private void refreshHomeCategoriesUI() {
        tvCountAll.setText(String.format(Locale.getDefault(), "%,d", allMediaList.size()));
        tvCountCamera.setText(String.format(Locale.getDefault(), "%,d", cameraList.size()));
        tvCountScreenshots.setText(String.format(Locale.getDefault(), "%,d", screenshotList.size()));
        tvCountVideos.setText(String.format(Locale.getDefault(), "%,d", videoList.size()));

        if (!allMediaList.isEmpty()) Glide.with(this).load(allMediaList.get(0).uri).into(ivCoverAll);
        if (!cameraList.isEmpty()) Glide.with(this).load(cameraList.get(0).uri).into(ivCoverCamera);
        if (!screenshotList.isEmpty()) Glide.with(this).load(screenshotList.get(0).uri).into(ivCoverScreenshots);
        if (!videoList.isEmpty()) Glide.with(this).load(videoList.get(0).uri).into(ivCoverVideos);

        cardAll.setOnClickListener(v -> openCategoryGrid(allMediaList, "所有照片"));
        cardCamera.setOnClickListener(v -> openCategoryGrid(cameraList, "相机"));
        cardScreenshots.setOnClickListener(v -> openCategoryGrid(screenshotList, "截图"));
        cardVideos.setOnClickListener(v -> openCategoryGrid(videoList, "视频"));
    }

    private void openCategoryGrid(List<MediaItem> targetList, String title) {
        isViewingCategoryGrid = true;
        currentCategoryTitle = title;

        layoutHomeCategories.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);

        currentDisplayList.clear();
        currentDisplayList.addAll(targetList);
        adapter.notifyDataSetChanged();

        tvTitle.setText(title + " (" + targetList.size() + ")");
        tvLeftAction.setText("返回");
        tvLeftAction.setVisibility(View.VISIBLE);
        updateGlobalSelectionUI();
    }

    private void exitCategoryGrid() {
        isViewingCategoryGrid = false;
        recyclerView.setVisibility(View.GONE);
        layoutHomeCategories.setVisibility(View.VISIBLE);

        tvTitle.setText("我的相册");
        tvLeftAction.setText("☰菜单");
        adapter.clearSelection();
        updateGlobalSelectionUI();
    }

    private void toggleImmersiveMode() {
        isImmersiveMode = !isImmersiveMode;
        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (isImmersiveMode) {
            if (windowInsetsController != null) {
                windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            }
            layoutTopBar.animate().alpha(0f).translationY(-layoutTopBar.getHeight()).setDuration(200)
                    .withEndAction(() -> layoutTopBar.setVisibility(View.GONE)).start();
            layoutBottomBar.animate().alpha(0f).translationY(layoutBottomBar.getHeight()).setDuration(200)
                    .withEndAction(() -> layoutBottomBar.setVisibility(View.GONE)).start();
        } else {
            if (windowInsetsController != null) {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            }
            layoutTopBar.setVisibility(View.VISIBLE);
            layoutTopBar.animate().alpha(1f).translationY(0).setDuration(200).start();
            layoutBottomBar.setVisibility(View.VISIBLE);
            layoutBottomBar.animate().alpha(1f).translationY(0).setDuration(200).start();
        }
    }

    private void startDatabaseSearchTask(String keyword) {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(60, 60, 60, 60);
        TextView tvProgress = new TextView(this); tvProgress.setText("正在极速查库..."); tvProgress.setTextSize(16); tvProgress.setTextColor(Color.BLACK);
        layout.addView(tvProgress);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("闪电检索").setView(layout).setCancelable(false).show();

        executorService.execute(() -> {
            List<String> matchedUris = AppDatabase.getInstance(this).ocrDao().searchImagesByKeyword(keyword);
            searchResultList.clear();
            for (String uriStr : matchedUris) {
                for (MediaItem item : allMediaList) {
                    if (item.uri.toString().equals(uriStr)) {
                        searchResultList.add(item);
                        break;
                    }
                }
            }

            runOnUiThread(() -> {
                dialog.dismiss();
                if (searchResultList.isEmpty()) {
                    Toast.makeText(this, "未找到包含“" + keyword + "”的图片（如果刚存入新图，请等待后台建库完成）", Toast.LENGTH_LONG).show();
                } else {
                    isViewingSearch = true; isViewingSimilar = false;
                    layoutHomeCategories.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.GONE);
                    layoutSimilarContainer.setVisibility(View.GONE);
                    layoutSearchContainer.setVisibility(View.VISIBLE);
                    tvSearchHeader.setText("以下是包含“" + keyword + "”的图片 (" + searchResultList.size() + "张)");
                    tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE);
                    searchAdapter.notifyDataSetChanged(); updateGlobalSelectionUI();
                }
            });
        });
    }

    // ================= 🚨 重构 2：闪电秒查 GIF =================
    private void startGifSearchTask() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);
        TextView tvProgress = new TextView(this);
        tvProgress.setText("正在从底层数据库极速提取动图...");
        tvProgress.setTextSize(16);
        tvProgress.setTextColor(android.graphics.Color.BLACK);
        layout.addView(tvProgress);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("闪电查找动图").setView(layout).setCancelable(false).show();

        executorService.execute(() -> {
            // 🚨 直接从数据库读取所有被标记为 GIF 的 URI (耗时约 5 毫秒)
            List<String> gifUris = AppDatabase.getInstance(MainActivity.this).ocrDao().getAllGifUris();
            java.util.HashSet<String> gifSet = new java.util.HashSet<>(gifUris);

            searchResultList.clear();
            for (MediaItem item : allMediaList) {
                if (item.type == MediaItem.MediaType.IMAGE && gifSet.contains(item.uri.toString())) {
                    searchResultList.add(item);
                }
            }

            searchResultList.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));

            runOnUiThread(() -> {
                dialog.dismiss();
                if (searchResultList.isEmpty()) {
                    Toast.makeText(MainActivity.this, "未找到动图 (如果刚装 App，请等待后台建库完成)", Toast.LENGTH_SHORT).show();
                } else {
                    isViewingSearch = true; isViewingSimilar = false;
                    layoutHomeCategories.setVisibility(View.GONE); recyclerView.setVisibility(View.GONE);
                    layoutSimilarContainer.setVisibility(View.GONE); layoutSearchContainer.setVisibility(View.VISIBLE);

                    tvSearchHeader.setText("以下是所有的真·动图 (" + searchResultList.size() + "张)");
                    tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE);
                    searchAdapter.notifyDataSetChanged(); updateGlobalSelectionUI();
                }
            });
        });
    }

    /**
     * 🚨 混合双通道读取：先用 File API 极速读取，被拒后再用 ContentResolver 兜底
     */
    private boolean isActualGif(MediaItem item) {
        // 外部 ACTION_VIEW 传入的 Uri 可能没有真实 path，但 MIME 仍然可靠。
        if ("image/gif".equalsIgnoreCase(item.mimeType)) return true;

        if (item.path == null) {
            try (java.io.InputStream is = getContentResolver().openInputStream(item.uri)) {
                return is != null && checkMagicBytes(is);
            } catch (Exception ignored) {
                return false;
            }
        }

        // 1. 毫秒级放行：如果扩展名已经明确是 GIF，直接秒过，不耗费任何 IO
        if (item.path.toLowerCase().endsWith(".gif")) {
            return true;
        }

        // 2. 极速硬盘通道：直接用 File API 暴力读硬盘（在拥有所有文件管理权限的 App 中，此法比系统 ContentProvider 快近百倍）
        try (java.io.FileInputStream fis = new java.io.FileInputStream(item.path)) {
            return checkMagicBytes(fis);
        } catch (Exception e) {
            // 3. 兜底慢速通道：只有当 File API 撞到 Android 沙盒拦截报错时，才动用 ContentResolver 进行跨进程读取
            try (java.io.InputStream is = getContentResolver().openInputStream(item.uri)) {
                if (is != null) return checkMagicBytes(is);
            } catch (Exception ex) {
                // 彻底读不到，放弃这张图
            }
        }
        return false;
    }

    /**
     * 提取出的公共方法：判断流中的魔数特征
     */
    private boolean checkMagicBytes(java.io.InputStream is) throws java.io.IOException {
        byte[] header = new byte[12];
        int bytesRead = is.read(header);

        if (bytesRead >= 3) {
            // GIF 动图魔数 (GIF87a / GIF89a)
            if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
                return true;
            }
        }

        if (bytesRead >= 12) {
            // WebP 动图魔数 (RIFF .... WEBP) 微信、QQ表情包绝大多数是这个格式
            if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' &&
                    header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                return true;
            }
        }
        return false;
    }

    private void showDatePickerDialog() {
        Calendar calendar = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> searchPhotosByDate(year, month, dayOfMonth),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void searchPhotosByDate(int year, int month, int day) {
        executorService.execute(() -> {
            Calendar startCal = Calendar.getInstance();
            startCal.set(year, month, day, 0, 0, 0);
            long startTime = startCal.getTimeInMillis() / 1000;

            Calendar endCal = Calendar.getInstance();
            endCal.set(year, month, day, 23, 59, 59);
            long endTime = endCal.getTimeInMillis() / 1000;

            String dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, day);

            searchResultList.clear();
            for (MediaItem item : allMediaList) {
                if (item.dateAdded >= startTime && item.dateAdded <= endTime) {
                    searchResultList.add(item);
                }
            }

            runOnUiThread(() -> {
                if (searchResultList.isEmpty()) {
                    Toast.makeText(MainActivity.this, dateStr + " 没有找到任何照片或视频", Toast.LENGTH_SHORT).show();
                } else {
                    isViewingSearch = true; isViewingSimilar = false;
                    layoutHomeCategories.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.GONE); layoutSimilarContainer.setVisibility(View.GONE);
                    layoutSearchContainer.setVisibility(View.VISIBLE);
                    tvSearchHeader.setText("以下是 " + dateStr + " 创建的照片 (" + searchResultList.size() + "张)");
                    tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE);
                    searchAdapter.notifyDataSetChanged(); updateGlobalSelectionUI();
                }
            });
        });
    }

    // ================= 🚨 纯净实用版：闪电秒查无字图 =================
    private void startNoTextSearchTask() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);
        TextView tvProgress = new TextView(this);
        tvProgress.setText("正在执行多维交叉比对，绝对剔除照片与动图...");
        tvProgress.setTextSize(16);
        tvProgress.setTextColor(android.graphics.Color.BLACK);
        layout.addView(tvProgress);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("过滤无字纯图").setView(layout).setCancelable(false).show();

        executorService.execute(() -> {
            // 1. 获取所有“无字图”的 URI
            List<String> noTextUris = AppDatabase.getInstance(MainActivity.this).ocrDao().getNoTextUris();
            java.util.HashSet<String> noTextSet = new java.util.HashSet<>(noTextUris);

            // 2. 获取所有“GIF 动图”的 URI
            List<String> gifUris = AppDatabase.getInstance(MainActivity.this).ocrDao().getAllGifUris();
            java.util.HashSet<String> gifSet = new java.util.HashSet<>(gifUris);

            // 3. 提取首页“相机”绝对黑名单
            java.util.HashSet<String> cameraUriSet = new java.util.HashSet<>();
            if (cameraList != null) {
                for (MediaItem cameraItem : cameraList) {
                    cameraUriSet.add(cameraItem.uri.toString());
                }
            }

            searchResultList.clear();
            for (MediaItem item : allMediaList) {
                if (item.type == MediaItem.MediaType.IMAGE) {

                    // 🚨 拦截 A：相机照片双重保障
                    // 第一层：在首页相机黑名单里
                    boolean isCamera = cameraUriSet.contains(item.uri.toString());
                    // 第二层：路径包含常见厂商的相机客制化目录
                    if (!isCamera && item.path != null) {
                        String lowerPath = item.path.toLowerCase();
                        if (!lowerPath.contains("screenshot") && !lowerPath.contains("截屏")) {
                            if (lowerPath.contains("camera") || lowerPath.contains("dcim/10") || lowerPath.contains("apple10")) {
                                isCamera = true;
                            }
                        }
                    }

                    // 🚨 拦截 B：动图三维拦截（数据库黑名单 OR 后缀 OR 系统 MIME 属性）
                    boolean isGif = gifSet.contains(item.uri.toString()) ||
                            (item.path != null && item.path.toLowerCase().endsWith(".gif")) ||
                            ("image/gif".equalsIgnoreCase(item.mimeType));

                    // 最终放行：必须在“无字图”数据库中，且绝对不是相机照片，绝对不是动图
                    if (noTextSet.contains(item.uri.toString()) && !isCamera && !isGif) {
                        searchResultList.add(item);
                    }
                }
            }

            // 按时间倒序排列 (最新的在最前面)
            searchResultList.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));

            runOnUiThread(() -> {
                dialog.dismiss();
                if (searchResultList.isEmpty()) {
                    Toast.makeText(MainActivity.this, "过滤完毕：未找到符合条件的纯图", Toast.LENGTH_SHORT).show();
                } else {
                    isViewingSearch = true; isViewingSimilar = false;
                    layoutHomeCategories.setVisibility(View.GONE); recyclerView.setVisibility(View.GONE);
                    layoutSimilarContainer.setVisibility(View.GONE); layoutSearchContainer.setVisibility(View.VISIBLE);

                    tvSearchHeader.setText("以下是排除照片和动图后的纯静态图 (" + searchResultList.size() + "张)");
                    tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE);
                    searchAdapter.notifyDataSetChanged(); updateGlobalSelectionUI();
                }
            });
        });
    }

    // ================= 🚨 重构 1：全局极速后台扫描引擎 =================
    private void startBackgroundOcrIndexing() {
        // 同一时刻只允许一条全局 OCR 索引任务，避免多次 onResume 把 7000 张任务重复排队。
        if (!ocrIndexingRunning.compareAndSet(false, true)) return;

        if (isFirstLaunch) {
            Toast.makeText(this, "系统正在后台构建全局极速索引 (动图 & OCR)...", Toast.LENGTH_SHORT).show();
            isFirstLaunch = false;
        }
        final java.util.List<MediaItem> snapshotList = new java.util.ArrayList<>(allMediaList);

        executorService.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                OcrDao dao = AppDatabase.getInstance(this).ocrDao();
                int newIndexedCount = 0;

                for (MediaItem item : snapshotList) {
                    if (item.type != MediaItem.MediaType.IMAGE) continue;
                    if (isUriInDeleteGracePeriod(item.uri)) continue;

                    // 用户在查看页主动点“复制文字”时，让交互 OCR 优先于后台建库。
                    while (userOcrInProgress || galleryScrollBusy) {
                        try { Thread.sleep(60); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    Long savedDate = dao.getModifiedDate(item.uri.toString());
                    boolean needScan = (savedDate == null || Math.abs(savedDate - item.dateModified) > 5);

                    if (needScan) {
                        boolean isGif = isActualGif(item);
                        String extractedText = OcrEngine.getInstance().extractTextFromImage(this, item.uri);
                        boolean hasText = extractedText != null && !extractedText.trim().isEmpty();

                        // OCR 过程中如果用户把图片删了，不允许旧任务把数据库记录重新写回来。
                        if (!isUriInDeleteGracePeriod(item.uri)) {
                            dao.insertOcrData(new ImageOcrData(
                                    item.uri.toString(),
                                    item.dateModified,
                                    extractedText == null ? "" : extractedText,
                                    isGif,
                                    !hasText
                            ));
                            newIndexedCount++;
                        }
                    }
                }
                if (newIndexedCount > 0) {
                    android.util.Log.i("MyPic", "✅ 全局索引构建完成，本次新增: " + newIndexedCount + " 张照片。");
                }
            } finally {
                ocrIndexingRunning.set(false);
            }
        });
    }

    private void startScanning() {
        final int scanGeneration = mediaScanGeneration.incrementAndGet();
        executorService.execute(() -> {
            List<MediaItem> scanned = MediaScanner.scanAllMedia(this);

            // MediaStore 删除落盘可能比系统确认页返回慢几百毫秒。短时间内把刚删除的 Uri 当作 tombstone。
            scanned.removeIf(item -> isUriInDeleteGracePeriod(item.uri));

            runOnUiThread(() -> {
                // 有更新的扫描/删除动作发生时，旧扫描结果直接作废，不能覆盖当前列表。
                if (scanGeneration != mediaScanGeneration.get()) return;

                allMediaList.clear();
                allMediaList.addAll(scanned);

                categorizeMedia();
                refreshHomeCategoriesUI();

                if (isViewingCategoryGrid) {
                    currentDisplayList.clear();
                    if (currentCategoryTitle.equals("所有照片")) currentDisplayList.addAll(allMediaList);
                    else if (currentCategoryTitle.equals("相机")) currentDisplayList.addAll(cameraList);
                    else if (currentCategoryTitle.equals("截图")) currentDisplayList.addAll(screenshotList);
                    else if (currentCategoryTitle.equals("视频")) currentDisplayList.addAll(videoList);
                    tvTitle.setText(currentCategoryTitle + " (" + currentDisplayList.size() + ")");
                }

                adapter.notifyDataSetChanged();
                if (viewerAdapter != null) viewerAdapter.notifyDataSetChanged();
                updateGlobalSelectionUI();
                startBackgroundOcrIndexing();
            });
        });
    }

    private boolean isUriInDeleteGracePeriod(Uri uri) {
        if (uri == null) return false;
        String key = uri.toString();
        Long removedAt = recentlyRemovedUris.get(key);
        if (removedAt == null) return false;
        if (System.currentTimeMillis() - removedAt <= MEDIASTORE_DELETE_GRACE_MS) return true;
        recentlyRemovedUris.remove(key, removedAt);
        return false;
    }

    private void updateGlobalSelectionUI() {
        int count = adapter.selectedItems.size();
        if (!isViewingSingle) {
            btnCopy.setText("复制");
            btnCopy.setEnabled(true);
        }
        if (count > 0 || isViewingSingle || isViewingSimilar) {
            if (!isImmersiveMode) layoutBottomBar.setVisibility(View.VISIBLE);

            if (isViewingSingle) {
                MediaItem viewerItem = getCurrentViewerItem();
                boolean canCopyText = viewerItem != null && viewerItem.type == MediaItem.MediaType.IMAGE;
                tvTitle.setText("");
                btnCopy.setVisibility(canCopyText ? View.VISIBLE : View.GONE);
                btnCopy.setText("复制文字");
                btnCopy.setEnabled(canCopyText && !userOcrInProgress);
                btnMove.setVisibility(View.GONE);
                btnShare.setVisibility(View.VISIBLE);
                btnDelete.setVisibility(isViewingExternalImage ? View.GONE : View.VISIBLE);
                btnDelete.setText("删除"); btnDelete.setTextColor(Color.parseColor("#FF0000")); btnDelete.setEnabled(!isViewingExternalImage);
            } else if (isViewingSimilar) {
                btnCopy.setVisibility(View.GONE); btnMove.setVisibility(View.GONE); btnShare.setVisibility(View.GONE); btnDelete.setVisibility(View.VISIBLE);
                if (count > 0) { tvTitle.setText("已选 " + count + " 项冗余图"); btnDelete.setText("一键清理 (" + count + ")"); btnDelete.setTextColor(Color.parseColor("#FF0000")); btnDelete.setEnabled(true); } else { tvTitle.setText("清理相似照片"); btnDelete.setText("请勾选待删图"); btnDelete.setTextColor(Color.parseColor("#999999")); btnDelete.setEnabled(false); }
            } else if (isViewingSearch) {
                if (count > 0) { tvTitle.setText("已选 " + count + " 项"); btnCopy.setVisibility(View.VISIBLE); btnMove.setVisibility(View.VISIBLE); btnShare.setVisibility(View.VISIBLE); btnDelete.setVisibility(View.VISIBLE); btnDelete.setText("删除"); btnDelete.setTextColor(Color.parseColor("#FF0000")); btnDelete.setEnabled(true); } else { layoutBottomBar.setVisibility(View.GONE); tvTitle.setText("搜索结果"); }
            } else {
                if (count > 0) { tvTitle.setText("已选 " + count + " 项"); btnCopy.setVisibility(View.VISIBLE); btnMove.setVisibility(View.VISIBLE); btnShare.setVisibility(View.VISIBLE); btnDelete.setVisibility(View.VISIBLE); btnDelete.setText("删除"); btnDelete.setTextColor(Color.parseColor("#FF0000")); btnDelete.setEnabled(true); } else { layoutBottomBar.setVisibility(View.GONE); tvTitle.setText(currentCategoryTitle); }
            }
        } else {
            layoutBottomBar.setVisibility(View.GONE);
            if (isViewingCategoryGrid) tvTitle.setText(currentCategoryTitle + " (" + currentDisplayList.size() + ")");
            else tvTitle.setText(isViewingSearch ? "搜索结果" : "我的相册");
        }

        if (!isViewingSingle && !isViewingSimilar && isViewingCategoryGrid) {
            int totalTarget = isViewingSearch ? searchResultList.size() : currentDisplayList.size();
            if (totalTarget > 0 && count > 0) {
                tvRightAction.setVisibility(View.VISIBLE); tvLeftAction.setText("取消"); tvRightAction.setText(count == totalTarget ? "取消全选" : "全选");
            } else {
                tvRightAction.setVisibility(View.GONE);
            }
        } else {
            tvRightAction.setVisibility(View.GONE);
        }
    }

    private void exitSpecialMode() {
        isViewingSimilar = false; isViewingSearch = false; adapter.clearSelection();
        layoutSimilarContainer.setVisibility(View.GONE); layoutSearchContainer.setVisibility(View.GONE);

        if (isViewingCategoryGrid) {
            recyclerView.setVisibility(View.VISIBLE);
        } else {
            layoutHomeCategories.setVisibility(View.VISIBLE);
        }
        tvLeftAction.setText(isViewingCategoryGrid ? "返回" : "☰菜单");
        tvRightAction.setVisibility(View.GONE);
        updateGlobalSelectionUI();
    }

    private class SimilarGroupAdapter extends RecyclerView.Adapter<SimilarGroupAdapter.GroupVH> {
        @NonNull @Override public GroupVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            CardView c = new CardView(p.getContext()); c.setLayoutParams(new ViewGroup.MarginLayoutParams(-1, -2)); ((ViewGroup.MarginLayoutParams)c.getLayoutParams()).setMargins(20,20,20,20);
            c.setRadius(16f); c.setCardElevation(8f); c.setCardBackgroundColor(Color.WHITE);
            LinearLayout l = new LinearLayout(p.getContext()); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(20,30,20,30);
            TextView title = new TextView(p.getContext()); title.setTextSize(15); title.setTextColor(Color.DKGRAY); title.setPadding(0,0,0,20);
            RecyclerView r = new RecyclerView(p.getContext()); r.setLayoutManager(new GridLayoutManager(p.getContext(), 4));
            l.addView(title); l.addView(r); c.addView(l); return new GroupVH(c, title, r);
        }
        @Override public void onBindViewHolder(@NonNull GroupVH h, int pos) {
            SimilarGroup g = similarGroupsResult.get(pos);
            h.title.setText(String.format(Locale.getDefault(), "相似组 %d (相似度: %.1f%%) - 建议勾选冗余图", pos + 1, g.averageSimilarity));
            MediaAdapter ia = new MediaAdapter(MainActivity.this, g.similarItems, new MediaAdapter.OnMediaInteractionListener() {
                @Override public void onSingleClick(MediaItem item) {
                    if (adapter.selectedItems.contains(item)) adapter.selectedItems.remove(item); else adapter.selectedItems.add(item);
                    adapter.isMultiSelectMode = true; updateGlobalSelectionUI(); if (h.innerRecycler.getAdapter() != null) h.innerRecycler.getAdapter().notifyDataSetChanged();
                }
                @Override public void onStartDragSelect(int p) {}
                @Override public void onSelectionChanged(int c) {
                    adapter.isMultiSelectMode = true;
                    updateGlobalSelectionUI();
                }
            });
            ia.selectedItems = adapter.selectedItems; ia.isMultiSelectMode = true; h.innerRecycler.setAdapter(ia);
        }
        @Override public int getItemCount() { return similarGroupsResult.size(); }
        class GroupVH extends RecyclerView.ViewHolder { TextView title; RecyclerView innerRecycler; GroupVH(View v, TextView t, RecyclerView r) { super(v); title = t; innerRecycler = r; } }
    }

    private void startSimilarScanTask() {
        List<MediaItem> imageItems = new ArrayList<>(); for (MediaItem item : allMediaList) if (item.type == MediaItem.MediaType.IMAGE) imageItems.add(item);
        if (imageItems.size() < 2) return;
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(60, 60, 60, 60);
        TextView tvProgress = new TextView(this); tvProgress.setTextSize(16); tvProgress.setTextColor(Color.BLACK); tvProgress.setPadding(0, 0, 0, 30);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progressBar.setMax(imageItems.size());
        layout.addView(tvProgress); layout.addView(progressBar);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("扫描相似冗余图片").setView(layout).setCancelable(false).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SimilarityEngine.startScan(this, imageItems, executorService, new SimilarityEngine.ScanCallback() {
            @Override public void onProgress(String stage, int current, int total) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        int safeTotal = Math.max(1, total);
                        progressBar.setMax(safeTotal);
                        progressBar.setProgress(Math.max(0, Math.min(current, safeTotal)));
                        int percent = Math.max(0, Math.min(100, Math.round(current * 100f / safeTotal)));
                        tvProgress.setText(stage + ": " + percent + "% (" + current + "/" + total + ")");
                    }
                });
            }
            @Override public void onComplete(List<SimilarGroup> result, long timeTakenMs) {
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); dialog.dismiss();
                    if (result.isEmpty()) Toast.makeText(MainActivity.this, "未发现高相似冗余图片", Toast.LENGTH_LONG).show();
                    else { similarGroupsResult.clear(); similarGroupsResult.addAll(result); isViewingSimilar = true; isViewingSearch = false; layoutHomeCategories.setVisibility(View.GONE); recyclerView.setVisibility(View.GONE); layoutSearchContainer.setVisibility(View.GONE); layoutSimilarContainer.setVisibility(View.VISIBLE); tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE); updateGlobalSelectionUI(); groupAdapter.notifyDataSetChanged(); }
                });
            }
        });
    }

    private void requestBatchSafAuth(Runnable task) {
        if (adapter.selectedItems.isEmpty() && !isViewingSimilar && allMediaList.isEmpty()) { task.run(); return; }
        List<MediaItem> itemsToCheck = new ArrayList<>();
        if (adapter.selectedItems.isEmpty()) itemsToCheck.addAll(allMediaList); else itemsToCheck.addAll(adapter.selectedItems);

        pendingAuthQueue.clear();
        List<String> reqFolders = new ArrayList<>();
        String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();

        for (MediaItem item : itemsToCheck) {
            if (item.path != null && item.path.startsWith(basePath)) {
                String rel = item.path.substring(basePath.length()).replaceFirst("^/", "");
                String root = rel.split("/")[0];
                String existingUri = prefs.getString("saf_uri_" + root, null);
                if (existingUri != null) {
                    String decodedUri = Uri.decode(existingUri);
                    if (!decodedUri.endsWith(":" + root) && !decodedUri.endsWith("/" + root)) { prefs.edit().remove("saf_uri_" + root).apply(); existingUri = null; }
                }
                if (!reqFolders.contains(root) && existingUri == null) reqFolders.add(root);
            }
        }
        if (reqFolders.isEmpty()) task.run(); else { pendingAuthQueue.addAll(reqFolders); onAuthCompleteTask = task; processNextAuth(); }
    }

    private void processNextAuth() {
        if (pendingAuthQueue.isEmpty()) { if (onAuthCompleteTask != null) { onAuthCompleteTask.run(); onAuthCompleteTask = null; } return; }
        String target = pendingAuthQueue.poll(); prefs.edit().putString("current_auth_target", target).apply();
        Toast.makeText(this, "👉 请直接无脑点击底部的【使用此文件夹】", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + target); intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri); }
        safLauncher.launch(intent);
    }

    private void showSafTransferDialog(boolean isMove) {
        if (adapter.selectedItems.isEmpty()) return;
        EditText editText = new EditText(this); editText.setText("MyPic");
        new AlertDialog.Builder(this).setTitle(isMove ? "安全移动" : "安全复制").setMessage("将在 Pictures 目录下处理：").setView(editText)
                .setPositiveButton("执行", (d, w) -> performSafTransferTask(editText.getText().toString().trim().isEmpty() ? "MyPic" : editText.getText().toString().trim(), isMove)).setNegativeButton("取消", null).show();
    }

    private void performSafTransferTask(String destFolderName, boolean isMove) {
        final int totalItems = adapter.selectedItems.size();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(60, 60, 60, 60);
        TextView tvProgress = new TextView(this); tvProgress.setTextColor(Color.BLACK); tvProgress.setPadding(0, 0, 0, 30);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progressBar.setMax(totalItems);
        layout.addView(tvProgress); layout.addView(progressBar);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(isMove ? "正在安全移动..." : "正在安全复制...").setView(layout).setCancelable(false).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        executorService.execute(() -> {
            int success = 0, fail = 0; pendingRemoveItems.clear(); String treeUriStr = prefs.getString("saf_uri_Pictures", null);
            if (treeUriStr == null) { runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(MainActivity.this, "缺少授权", Toast.LENGTH_SHORT).show(); }); return; }
            Uri targetParentUri = SafFileManager.getOrCreateTargetDirectory(this, treeUriStr, destFolderName);
            if (targetParentUri == null) { runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(MainActivity.this, "无法创建文件夹", Toast.LENGTH_SHORT).show(); }); return; }

            int idx = 0;
            for (MediaItem item : adapter.selectedItems) {
                idx++; String newName = new File(item.path).getName();
                if (SafFileManager.copyFile(this, item.uri, targetParentUri, item.mimeType, newName)) {
                    success++;
                    if (isMove) {
                        String root = getRootFolder(item.path);
                        if (SafFileManager.deleteFile(this, item.path, item.uri, prefs.getString("saf_uri_" + root, null))) {
                            pendingRemoveItems.add(item);
                        }
                    }
                } else fail++;

                if (idx % 10 == 0 || idx == totalItems) { final int c = idx; runOnUiThread(() -> { if (dialog.isShowing()) { tvProgress.setText("进度: " + c + "/" + totalItems); progressBar.setProgress(c); } }); }
            }
            int fS = success, fF = fail;
            runOnUiThread(() -> { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); dialog.dismiss(); if (isMove) { removePendingItemsFromUI(); } refreshAfterAction(); Toast.makeText(this, "完成: 成功 " + fS + "，失败 " + fF, Toast.LENGTH_LONG).show(); });
        });
    }

    private void performSafTrashTask() {
        final int totalItems = adapter.selectedItems.size();
        if (totalItems == 0) return;

        new AlertDialog.Builder(this).setTitle("安全清理").setMessage("确定删除这 " + totalItems + " 项照片吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // 必须在跳到系统删除确认页之前保存稳定快照。确认页返回时，onResume 可能已经触发过扫描，
                        // adapter.selectedItems 里的对象不能再作为“本次真正删除了谁”的唯一依据。
                        pendingSystemDeleteItems.clear();
                        pendingSystemDeleteItems.addAll(new ArrayList<>(adapter.selectedItems));

                        List<Uri> urisToDelete = new ArrayList<>();
                        for (MediaItem item : pendingSystemDeleteItems) urisToDelete.add(item.uri);

                        lastLocalActionTime = System.currentTimeMillis();
                        mediaScanGeneration.incrementAndGet(); // 让尚未返回的旧 MediaStore 扫描结果立即失效
                        try {
                            android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), urisToDelete);
                            startIntentSenderForResult(pi.getIntentSender(), 3000, null, 0, 0, 0, null);
                        } catch (Exception e) {
                            pendingSystemDeleteItems.clear();
                            Log.e("Delete", "请求批量删除失败", e);
                            Toast.makeText(this, "无法发起系统删除请求", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(60, 60, 60, 60);
                    TextView tvProgress = new TextView(this); tvProgress.setTextColor(Color.BLACK); tvProgress.setPadding(0, 0, 0, 30);
                    ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progressBar.setMax(totalItems);
                    layout.addView(tvProgress); layout.addView(progressBar);
                    AlertDialog progressDialog = new AlertDialog.Builder(this).setTitle("正在清理...").setView(layout).setCancelable(false).show();
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                    executorService.execute(() -> {
                        int success = 0, fail = 0; pendingRemoveItems.clear(); int idx = 0;
                        for (MediaItem item : adapter.selectedItems) {
                            idx++; String root = getRootFolder(item.path); boolean deleted = false;
                            try { deleted = SafFileManager.deleteFile(this, item.path, item.uri, prefs.getString("saf_uri_" + root, null)); } catch (Exception e) { Log.e("Delete", "SAF删除被系统拒绝", e); }
                            if (deleted) { success++; pendingRemoveItems.add(item); } else { fail++; prefs.edit().remove("saf_uri_" + root).apply(); }
                            if (idx % 10 == 0 || idx == totalItems) { final int c = idx; runOnUiThread(() -> { if (progressDialog.isShowing()) { progressBar.setProgress(c); tvProgress.setText("进度: " + c + "/" + totalItems); } }); }
                        }
                        int fS = success, fF = fail;
                        runOnUiThread(() -> { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); progressDialog.dismiss(); removePendingItemsFromUI(); Toast.makeText(this, "清理完成: 成功 " + fS + " 项，失败 " + fF + " 项\n(失败通常为授权错位，已重置，下次请重试)", Toast.LENGTH_LONG).show(); });
                    });
                }).setNegativeButton("取消", null).show();
    }

    private String getRootFolder(String absolutePath) {
        String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (absolutePath != null && absolutePath.startsWith(basePath) && absolutePath.length() > basePath.length()) { String relative = absolutePath.substring(basePath.length()).replaceFirst("^/", ""); return relative.split("/")[0]; } return "";
    }

    private void removePendingItemsFromUI() {
        if (pendingRemoveItems.isEmpty()) return;

        // 真实删除/移动完成：失效所有旧扫描，并给 MediaStore 一个短暂同步窗口。
        long now = System.currentTimeMillis();
        MainActivity.lastLocalActionTime = now;
        mediaScanGeneration.incrementAndGet();
        for (MediaItem item : pendingRemoveItems) {
            if (item.uri != null) recentlyRemovedUris.put(item.uri.toString(), now);
        }

        allMediaList.removeAll(pendingRemoveItems);
        cameraList.removeAll(pendingRemoveItems);
        screenshotList.removeAll(pendingRemoveItems);
        videoList.removeAll(pendingRemoveItems);
        currentDisplayList.removeAll(pendingRemoveItems);

        refreshHomeCategoriesUI();

        List<MediaItem> itemsToDelete = new ArrayList<>(pendingRemoveItems);
        executorService.execute(() -> {
            for(MediaItem item : itemsToDelete) {
                try {
                    OcrDao dao = AppDatabase.getInstance(this).ocrDao();
                    dao.deleteByUri(item.uri.toString());
                    dao.deleteSimilarityFingerprintByUri(item.uri.toString());
                } catch (Exception e) { Log.e("Database", "删除数据库记录失败", e); }
            }
        });

        if (isViewingSimilar) {
            List<SimilarGroup> emptyGroups = new ArrayList<>();
            for (SimilarGroup group : similarGroupsResult) { group.similarItems.removeAll(pendingRemoveItems); if (group.similarItems.size() < 2) emptyGroups.add(group); }
            similarGroupsResult.removeAll(emptyGroups); groupAdapter.notifyDataSetChanged();
            if (similarGroupsResult.isEmpty()) exitSpecialMode();
        }

        if (isViewingSearch) {
            searchResultList.removeAll(pendingRemoveItems); searchAdapter.notifyDataSetChanged();
            if (searchResultList.isEmpty()) exitSpecialMode(); else tvSearchHeader.setText("以下是包含“" + etOcrSearch.getText().toString().trim() + "”的图片 (" + searchResultList.size() + "张)");
        }

        adapter.selectedItems.clear(); pendingRemoveItems.clear(); adapter.notifyDataSetChanged();
        if (viewerAdapter != null) viewerAdapter.notifyDataSetChanged();

        if (isViewingSingle) {
            List<MediaItem> targetList = isViewingSearch ? searchResultList : currentDisplayList;
            if (targetList.isEmpty()) { closeViewer(); } else {
                int currentPos = viewPager.getCurrentItem();
                if (currentPos >= targetList.size()) currentPos = targetList.size() - 1;
                MediaItem currentNewItem = targetList.get(currentPos);
                adapter.selectedItems.add(currentNewItem);
                tvRightAction.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(currentNewItem.dateAdded * 1000)));
            }
        }
        updateGlobalSelectionUI();
    }

    private void refreshAfterAction() { exitMultiSelectMode(); if (isViewingSingle) closeViewer(); startScanning(); }

    private void enterSingleView(MediaItem item) {
        isViewingExternalImage = false;
        openedFromExternalIntent = false;
        openViewer(item, isViewingSearch ? searchResultList : currentDisplayList);
    }

    private void openViewer(MediaItem item, List<MediaItem> targetList) {
        isViewingSingle = true;
        adapter.selectedItems.clear(); adapter.selectedItems.add(item);
        recyclerView.setVisibility(View.INVISIBLE); layoutHomeCategories.setVisibility(View.GONE); layoutViewer.setVisibility(View.VISIBLE);
        tvLeftAction.setText("返回"); tvLeftAction.setVisibility(View.VISIBLE);

        viewerAdapter = new ViewerAdapter(this, targetList, new ViewerAdapter.OnViewerItemClickListener() {
            @Override public void onImageClick() { toggleImmersiveMode(); }
            @Override public void onVideoPlayClick(MediaItem vItem) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(vItem.uri, vItem.mimeType);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "未找到可以播放视频的应用", Toast.LENGTH_SHORT).show();
                }
            }
        });
        viewPager.setAdapter(viewerAdapter);
        viewPager.setCurrentItem(targetList.indexOf(item), false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                if (isViewingSingle) {
                    List<MediaItem> targetList = getViewerTargetList();
                    if (position < targetList.size()) {
                        MediaItem item = targetList.get(position);
                        adapter.selectedItems.clear(); adapter.selectedItems.add(item);
                        updateGlobalSelectionUI();
                        tvRightAction.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.dateAdded * 1000)));
                        tvRightAction.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        updateGlobalSelectionUI();
        tvRightAction.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.dateAdded * 1000)));
        tvRightAction.setVisibility(View.VISIBLE);
    }

    private List<MediaItem> getViewerTargetList() {
        if (isViewingExternalImage) return externalViewerList;
        return isViewingSearch ? searchResultList : currentDisplayList;
    }

    private MediaItem getCurrentViewerItem() {
        if (!isViewingSingle) return null;
        List<MediaItem> targetList = getViewerTargetList();
        if (targetList == null || targetList.isEmpty()) return null;
        int position = viewPager.getCurrentItem();
        if (position < 0 || position >= targetList.size()) return null;
        return targetList.get(position);
    }

    /**
     * 查看页一键复制图片文字：优先复用后台 OCR 数据库；图片新增/修改时才现场识别。
     */
    private void copyCurrentImageText() {
        MediaItem item = getCurrentViewerItem();
        if (item == null || item.type != MediaItem.MediaType.IMAGE) {
            Toast.makeText(this, "当前项目不是可识别图片", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout progressLayout = new LinearLayout(this);
        progressLayout.setOrientation(LinearLayout.HORIZONTAL);
        progressLayout.setPadding(48, 36, 48, 36);
        progressLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ProgressBar progress = new ProgressBar(this);
        TextView message = new TextView(this);
        message.setText("  正在识别并复制图片文字…");
        message.setTextSize(16);
        message.setTextColor(Color.BLACK);
        progressLayout.addView(progress);
        progressLayout.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("复制图片文字")
                .setView(progressLayout)
                .setCancelable(false)
                .show();

        btnCopy.setEnabled(false);
        userOcrInProgress = true;

        executorService.execute(() -> {
            String resultText = null;
            boolean failed = false;
            try {
                OcrDao dao = AppDatabase.getInstance(this).ocrDao();
                Long savedDate = dao.getModifiedDate(item.uri.toString());
                String orderedCacheKey = "ordered_ocr_v3:" + item.uri;
                boolean orderedCacheReady = prefs.getBoolean(orderedCacheKey, false);

                // v3 同时升级长图区域解码、切片去重和阅读顺序。旧缓存无法判断是否由旧长图算法生成，
                // 因此某张图片升级后第一次点“复制文字”时仅重识别这一张并覆盖缓存；以后仍是毫秒级读取。
                if (orderedCacheReady && savedDate != null && Math.abs(savedDate - item.dateModified) <= 5) {
                    resultText = dao.getExtractedText(item.uri.toString());
                } else {
                    resultText = OcrEngine.getInstance().extractTextFromImage(this, item.uri);
                    if (resultText != null && !isUriInDeleteGracePeriod(item.uri)) {
                        boolean isGif = isActualGif(item);
                        boolean hasText = !resultText.trim().isEmpty();
                        dao.insertOcrData(new ImageOcrData(
                                item.uri.toString(), item.dateModified, resultText, isGif, !hasText));
                        prefs.edit().putBoolean(orderedCacheKey, true).apply();
                    }
                }
            } catch (Exception e) {
                failed = true;
                Log.e("CopyImageText", "图片文字识别失败", e);
            }

            String finalText = resultText == null ? "" : resultText.trim();
            boolean finalFailed = failed;
            runOnUiThread(() -> {
                userOcrInProgress = false;
                if (dialog.isShowing()) dialog.dismiss();
                if (isViewingSingle) btnCopy.setEnabled(true);

                if (finalFailed) {
                    Toast.makeText(this, "文字识别失败，请重试", Toast.LENGTH_SHORT).show();
                } else if (finalText.isEmpty()) {
                    Toast.makeText(this, "未识别到可复制文字", Toast.LENGTH_SHORT).show();
                } else {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("图片文字", finalText));
                    Toast.makeText(this, "已复制识别文字（" + finalText.length() + " 字符）", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void enterMultiSelectUI() { tvLeftAction.setText("取消"); tvLeftAction.setVisibility(View.VISIBLE); updateGlobalSelectionUI(); }
    private void handleDragSelect(RecyclerView rv, MotionEvent e, List<MediaItem> targetList) { View child = rv.findChildViewUnder(e.getX(), e.getY()); if (child != null) { int pos = rv.getChildAdapterPosition(child); if (pos != RecyclerView.NO_POSITION && pos != lastSelectedPosition) { MediaItem item = targetList.get(pos); if (!adapter.selectedItems.contains(item)) adapter.selectedItems.add(item); else adapter.selectedItems.remove(item); if (rv.getAdapter() != null) rv.getAdapter().notifyItemChanged(pos); lastSelectedPosition = pos; updateGlobalSelectionUI(); } } }

    private void closeViewer() {
        if (isImmersiveMode) toggleImmersiveMode();

        // 作为系统图片查看器被唤起时，“返回”应直接回到原文件管理器。
        if (openedFromExternalIntent) {
            openedFromExternalIntent = false;
            isViewingExternalImage = false;
            externalViewerList.clear();
            finish();
            return;
        }

        isViewingSingle = false;
        isViewingExternalImage = false;
        externalViewerList.clear();
        adapter.selectedItems.clear();
        layoutViewer.setVisibility(View.GONE);
        if (isViewingCategoryGrid) recyclerView.setVisibility(View.VISIBLE);
        else layoutHomeCategories.setVisibility(View.VISIBLE);

        tvLeftAction.setText(isViewingCategoryGrid ? "返回" : "☰菜单");
        updateGlobalSelectionUI();
    }

    private void exitMultiSelectMode() { adapter.clearSelection(); tvLeftAction.setText(isViewingCategoryGrid ? "返回" : "☰菜单"); updateGlobalSelectionUI(); }

    private void shareSelectedMedia() {
        if (adapter.selectedItems.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        boolean hasImage = false;
        boolean hasVideo = false;

        for (MediaItem item : adapter.selectedItems) {
            uris.add(item.uri);
            if (item.type == MediaItem.MediaType.IMAGE) {
                hasImage = true;
            } else if (item.type == MediaItem.MediaType.VIDEO) {
                hasVideo = true;
            }
        }

        Intent intent = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);

        if (uris.size() > 1) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }

        if (hasImage && !hasVideo) {
            intent.setType("image/*");
        } else if (!hasImage && hasVideo) {
            intent.setType("video/*");
        } else {
            intent.setType("*/*");
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享"));
    }

    /**
     * 快速滚动期间只移动列表，不加载沿途缩略图；松手后仅重新绑定最终屏幕。
     * 这能避免 7000 张规模下拖动一次右侧滑块产生数百个已过期解码任务。
     */
    private void setFastScrollThumbnailMode(RecyclerView rv, boolean active) {
        if (rv == null || !(rv.getAdapter() instanceof MediaAdapter)) return;
        MediaAdapter mediaAdapter = (MediaAdapter) rv.getAdapter();
        mediaAdapter.setFastScrollActive(active);

        RecyclerView.LayoutManager layoutManager = rv.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            GridLayoutManager grid = (GridLayoutManager) layoutManager;
            int first = grid.findFirstVisibleItemPosition();
            int last = grid.findLastVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION && last >= first) {
                mediaAdapter.notifyItemRangeChanged(first, last - first + 1);
            }
        }
    }

    private boolean hasLibraryReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean handleIncomingImageIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri uri = intent.getData();
        if (uri == null) return false;

        String mimeType = intent.getType();
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = getContentResolver().getType(uri);
        }
        if (mimeType == null || !mimeType.startsWith("image/")) return false;

        try {
            // 对 ACTION_VIEW 来说通常是临时 grant；能持久化时顺手保留，不能持久化也不影响当前查看。
            int takeFlags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ("content".equals(uri.getScheme()) && takeFlags != 0) {
                try { getContentResolver().takePersistableUriPermission(uri, takeFlags); } catch (Exception ignored) {}
            }

            long nowSeconds = System.currentTimeMillis() / 1000L;
            MediaItem externalItem = new MediaItem(uri, null, nowSeconds, 0L, mimeType, MediaItem.MediaType.IMAGE);
            externalViewerList.clear();
            externalViewerList.add(externalItem);
            layoutSearchContainer.setVisibility(View.GONE);
            layoutSimilarContainer.setVisibility(View.GONE);
            isViewingExternalImage = true;
            openedFromExternalIntent = true;
            openViewer(externalItem, externalViewerList);
            return true;
        } catch (Exception e) {
            Log.e("ExternalImage", "无法打开外部图片", e);
            Toast.makeText(this, "无法打开该图片", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingImageIntent(intent);
    }

    // ================= 🚨 终极适配：全机型权限动态申请 =================
    private void checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 📱 Android 13 及以上 (如 OriginOS 4, 澎湃 OS, Realme UI 4.0+)
            // 必须申请专属的 Images 和 Video 权限，老权限申请了系统也不会理你
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                }, 1000);
            } else {
                startScanning();
            }
        } else {
            // 📱 Android 12 及以下老手机
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 1000);
            } else {
                startScanning();
            }
        }
    }

    // ================= 🚨 修复版：带冷却防抖的静默刷新 =================
    @Override
    protected void onResume() {
        super.onResume();
        if (!isFirstLaunch) {
            // 外部 ACTION_VIEW 只依赖对方临时授予的 Uri 读取权限；没有图库权限时不要突然弹全库权限。
            if (openedFromExternalIntent && !hasLibraryReadPermission()) return;

            // 核心修复：如果距离上一次 App 内的删除等操作不到 2 秒，直接跳过本次系统刷新！
            // 给安卓底层的 MediaStore 留出同步删除记录的时间，彻底掐死幽灵数据
            if (System.currentTimeMillis() - lastLocalActionTime > 2000) {
                checkPermissionsAndScan();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1000) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted && grantResults.length > 0) {
                // 用户同意了权限，立刻开始扫描
                startScanning();
            } else {
                Toast.makeText(this, "核心权限被拒绝，相册无法加载！", Toast.LENGTH_LONG).show();
            }
        }
    }

    // 注意：同时删除原本 onActivityResult 里的这一行：
    // if (requestCode == 2000) checkPermissionsAndScan();
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
//        if (requestCode == 2000) checkPermissionsAndScan();
        if (requestCode == 3000) {
            if (resultCode == Activity.RESULT_OK) {
                pendingRemoveItems.clear();
                pendingRemoveItems.addAll(pendingSystemDeleteItems);
                int deletedCount = pendingRemoveItems.size();
                removePendingItemsFromUI();
                pendingSystemDeleteItems.clear();
                Toast.makeText(this, "✅ 成功删除 " + deletedCount + " 项", Toast.LENGTH_SHORT).show();
            } else {
                pendingSystemDeleteItems.clear();
                Toast.makeText(this, "❌ 您取消了删除", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
