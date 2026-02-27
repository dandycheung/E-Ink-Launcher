package cn.modificator.launcher.widgets;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.Set;

import cn.modificator.launcher.R;
import cn.modificator.launcher.model.AppDataCenter;
import cn.modificator.launcher.model.IconCache;
import cn.modificator.launcher.model.ObservableFloat;
import cn.modificator.launcher.model.WifiControl;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;

/**
 * E-Ink 桌面网格布局 ViewGroup。
 * <p>
 * 职责仅限于网格布局、子 View 管理、数据绑定显示与手势检测。
 * <ul>
 *   <li>业务动作（启动应用、弹出对话框等）通过 {@link Callback} 委派给宿主</li>
 *   <li>图标/标签加载由外部注入的 {@link IconCache} 管理</li>
 *   <li>不持有 Config 引用——所有配置项通过 setter 传入</li>
 * </ul>
 */
public class EInkLauncherView extends ViewGroup {

  // =========================================================================
  // 回调接口
  // =========================================================================

  /** 宿主实现此接口以处理所有用户交互。 */
  public interface Callback {
    /** 普通模式下点击应用 */
    void onItemClick(ResolveInfo info);

    /** 长按应用 */
    void onItemLongClick(View anchor, ResolveInfo info);

    /** 管理模式下点击卸载 */
    void onItemDeleteClick(ResolveInfo info);

    /** 管理模式下切换隐藏状态（视图已自行更新 UI 状态） */
    void onItemHideToggle(String packageName, boolean hidden);

    /** 滑动到下一页 */
    void onSwipeNext();

    /** 滑动到上一页 */
    void onSwipePrev();
  }

  // =========================================================================
  // ViewHolder
  // =========================================================================

  private static class ItemViewHolder {
    final ImageView appImage;
    final ObserverFontTextView appName;
    final View menuContainer;
    final View menuDelete;
    final View menuHide;

    ItemViewHolder(View itemView) {
      appImage = itemView.findViewById(R.id.appImage);
      appName = (ObserverFontTextView) itemView.findViewById(R.id.appName);
      menuContainer = ((ViewGroup) itemView).getChildAt(1);
      menuDelete = itemView.findViewById(R.id.menu_delete);
      menuHide = itemView.findViewById(R.id.menu_hide);
    }
  }

  // =========================================================================
  // 字段
  // =========================================================================

  // 网格参数
  private int rowNum = 5;
  private int colNum = 5;

  // 显示参数
  private float fontSize = 14;
  private int appNameLines = Integer.MAX_VALUE;
  private boolean hideDivider = false;

  // 状态
  private boolean isDelete = false;
  private boolean isSystemApp = false;

  // 数据
  private final List<ResolveInfo> dataList = new ArrayList<>();
  private final List<ItemViewHolder> holders = new ArrayList<>();
  private final Set<String> hideAppPkg = new HashSet<>();
  private final PackageManager packageManager;
  private final ObservableFloat fontSizeObservable = new ObservableFloat();

  // 外部依赖
  private Callback callback;
  private IconCache iconCache;

  // 滑动检测
  private float touchDownX;
  private float touchDownY;
  private float swipeThreshold;

  // =========================================================================
  // 构造器
  // =========================================================================

  public EInkLauncherView(Context context) {
    this(context, null);
  }

  public EInkLauncherView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EInkLauncherView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    packageManager = context.getPackageManager();
  }

  // =========================================================================
  // 外部依赖注入
  // =========================================================================

  public void setCallback(Callback callback) {
    this.callback = callback;
  }

  public void setIconCache(IconCache iconCache) {
    this.iconCache = iconCache;
  }

  // =========================================================================
  // 批量配置（初始化时一次性设定所有参数，只触发一次重建）
  // =========================================================================

  /**
   * 一次性配置所有网格与显示参数，仅触发一次 {@code resetGrid()}。
   * 推荐在初始化阶段使用以避免多次重建。
   */
  public void configure(int colNum, int rowNum, boolean hideDivider,
                         float fontSize, int appNameLines) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    this.hideDivider = hideDivider;
    this.fontSize = fontSize;
    this.appNameLines = appNameLines;
    fontSizeObservable.set(fontSize);
    resetGrid();
  }

  // =========================================================================
  // 单项属性设置（运行时设置页变更）
  // =========================================================================

  public void setGridSize(int colNum, int rowNum) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    resetGrid();
  }

  public void setColNum(int colNum) {
    this.colNum = colNum;
    resetGrid();
  }

  public void setRowNum(int rowNum) {
    this.rowNum = rowNum;
    resetGrid();
  }

  public void setHideDivider(boolean hideDivider) {
    this.hideDivider = hideDivider;
    resetGrid();
  }

  public void setFontSize(float fontSize) {
    this.fontSize = fontSize;
    fontSizeObservable.set(fontSize);
  }

  public float getFontSize() {
    return fontSize;
  }

  public void setAppNameLines(int lines) {
    this.appNameLines = lines;
    for (ItemViewHolder holder : holders) {
      holder.appName.setMinLines(lines == 2 ? lines : 0);
      holder.appName.setMaxLines(lines);
    }
  }

  public void setSystemApp(boolean systemApp) {
    isSystemApp = systemApp;
  }

  // =========================================================================
  // 管理模式
  // =========================================================================

  public void setDelete(boolean delete) {
    isDelete = delete;
    updateDeleteMode();
  }

  public boolean isDelete() {
    return isDelete;
  }

  // =========================================================================
  // 隐藏应用
  // =========================================================================

  public void setHideAppPkg(Set<String> hideAppPkg) {
    this.hideAppPkg.clear();
    this.hideAppPkg.addAll(hideAppPkg);
  }

  public Set<String> getHideAppPkg() {
    return hideAppPkg;
  }

  // =========================================================================
  // 数据
  // =========================================================================

  /** 设置当前页要显示的应用列表并刷新显示 */
  public void setAppList(List<ResolveInfo> appList) {
    dataList.clear();
    dataList.addAll(appList);
    bindAllItems();
  }

  /** 仅刷新显示（自定义图标变更后调用） */
  public void refreshDisplay() {
    bindAllItems();
  }

  // =========================================================================
  // onLayout / onMeasure
  // =========================================================================

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int w = getAdjustedWidth();
    int h = getAdjustedHeight();
    if (w <= 0 || h <= 0) return;

    swipeThreshold = Math.min(w, h) / 6f;
    int cellW = w / colNum;
    int cellH = h / rowNum;

    for (int row = 0; row < rowNum; row++) {
      for (int col = 0; col < colNum; col++) {
        int index = row * colNum + col;
        if (index >= getChildCount()) return;
        getChildAt(index).layout(
            col * cellW, row * cellH,
            (col + 1) * cellW, (row + 1) * cellH);
      }
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int w = getAdjustedWidth();
    int h = getAdjustedHeight();
    if (w <= 0 || h <= 0) return;

    int cellWSpec = makeMeasureSpec(w / colNum, EXACTLY);
    int cellHSpec = makeMeasureSpec(h / rowNum, EXACTLY);
    for (int i = 0; i < getChildCount(); i++) {
      getChildAt(i).measure(cellWSpec, cellHSpec);
    }
  }

  private int getAdjustedWidth() {
    return getWidth() - getPaddingLeft() - getPaddingRight();
  }

  private int getAdjustedHeight() {
    return getHeight() - getPaddingTop() - getPaddingBottom();
  }

  // =========================================================================
  // 网格构建
  // =========================================================================

  private void resetGrid() {
    int targetCount = rowNum * colNum;

    if (holders.size() == targetCount) {
      // 数量不变，仅刷新背景
      for (int i = 0; i < targetCount; i++) {
        getChildAt(i).setBackgroundResource(getItemBackground(i));
      }
      bindAllItems();
      return;
    }

    // 数量变化，完整重建
    fontSizeObservable.deleteObservers();
    removeAllViews();
    holders.clear();

    LayoutInflater inflater = LayoutInflater.from(getContext());
    for (int i = 0; i < targetCount; i++) {
      View itemView = inflater.inflate(R.layout.launcher_item, this, false);
      ItemViewHolder holder = new ItemViewHolder(itemView);
      holders.add(holder);

      fontSizeObservable.addObserver((Observer) holder.appName);
      holder.appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
      holder.appName.setMinLines(appNameLines == 2 ? appNameLines : 0);
      holder.appName.setMaxLines(appNameLines);
      holder.appName.setEllipsize(TextUtils.TruncateAt.END);

      itemView.setBackgroundResource(getItemBackground(i));
      addView(itemView);
    }
    bindAllItems();
  }

  private int getItemBackground(int index) {
    int total = rowNum * colNum;
    if (hideDivider || index == total - 1) {
      return R.drawable.app_item_final;
    } else if (index % colNum == colNum - 1) {
      return R.drawable.app_item_right;
    } else if (index >= (rowNum - 1) * colNum) {
      return R.drawable.app_item_bottom;
    } else {
      return R.drawable.app_item_normal;
    }
  }

  // =========================================================================
  // 数据绑定
  // =========================================================================

  private void bindAllItems() {
    Map<String, File> customIcons = iconCache != null ? iconCache.getCustomIconMap() : null;
    WifiControl.bind(null, customIcons);

    for (int i = 0; i < holders.size(); i++) {
      View child = getChildAt(i);
      ItemViewHolder holder = holders.get(i);

      if (i < dataList.size()) {
        bindItem(child, holder, i, customIcons);
      } else {
        clearItem(child, holder);
      }
    }
    updateDeleteMode();
  }

  private void bindItem(View itemView, ItemViewHolder holder, int position,
                         Map<String, File> customIcons) {
    ResolveInfo info = dataList.get(position);
    String pkg = info.activityInfo.packageName;

    // —— 图标 & 标签 ——
    if (AppDataCenter.WIFI_PACKAGE_NAME.equals(pkg)) {
      WifiControl.bind(itemView, customIcons);
    } else if (AppDataCenter.LOCK_PACKAGE_NAME.equals(pkg)) {
      loadIcon(holder.appImage, pkg, R.drawable.ic_onekeylock, customIcons);
      holder.appName.setText(R.string.item_lockscreen);
    } else {
      loadIcon(holder.appImage, pkg, info, customIcons);
      holder.appName.setText(iconCache != null
          ? iconCache.getLabel(pkg, info, packageManager)
          : info.loadLabel(packageManager));
    }

    // —— 监听器（通过 tag 传递 position，复用单例监听器） ——
    itemView.setTag(position);
    itemView.setOnClickListener(clickListener);
    itemView.setOnLongClickListener(longClickListener);
    holder.menuDelete.setTag(position);
    holder.menuDelete.setOnClickListener(deleteClickListener);
    holder.menuHide.setTag(position);
    holder.menuHide.setOnClickListener(hideClickListener);

    itemView.setVisibility(VISIBLE);
    itemView.setAlpha(1);
  }

  /** 加载图标，优先使用自定义图标替换 */
  private void loadIcon(ImageView iv, String pkg, int defaultRes,
                         Map<String, File> customIcons) {
    File custom = customIcons != null ? customIcons.get(pkg) : null;
    if (custom != null) {
      iv.setImageURI(Uri.fromFile(custom));
    } else {
      iv.setImageResource(defaultRes);
    }
  }

  /** 加载图标，优先使用自定义图标替换，其次从缓存加载 */
  private void loadIcon(ImageView iv, String pkg, ResolveInfo info,
                         Map<String, File> customIcons) {
    File custom = customIcons != null ? customIcons.get(pkg) : null;
    if (custom != null) {
      iv.setImageURI(Uri.fromFile(custom));
    } else {
      Drawable icon = iconCache != null
          ? iconCache.getIcon(pkg, info, packageManager)
          : info.loadIcon(packageManager);
      iv.setImageDrawable(icon);
    }
  }

  private void clearItem(View itemView, ItemViewHolder holder) {
    holder.appName.setText("");
    holder.appImage.setImageDrawable(null);
    itemView.setOnClickListener(null);
    itemView.setOnLongClickListener(null);
    holder.menuDelete.setOnClickListener(null);
    holder.menuHide.setOnClickListener(null);
    itemView.setAlpha(0);
  }

  // =========================================================================
  // 管理模式 UI
  // =========================================================================

  private void updateDeleteMode() {
    for (int i = 0; i < holders.size() && i < dataList.size(); i++) {
      ItemViewHolder holder = holders.get(i);

      if (!isDelete) {
        holder.menuContainer.setVisibility(GONE);
        continue;
      }

      holder.menuContainer.setVisibility(VISIBLE);
      String pkg = dataList.get(i).activityInfo.packageName;

      boolean canDelete = false;
      if (!AppDataCenter.WIFI_PACKAGE_NAME.equals(pkg)
          && !AppDataCenter.LOCK_PACKAGE_NAME.equals(pkg)) {
        try {
          canDelete = (packageManager.getPackageInfo(pkg, 0).applicationInfo.flags
              & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      }

      holder.menuDelete.setVisibility(canDelete ? VISIBLE : GONE);
      holder.menuHide.setSelected(hideAppPkg.contains(pkg));
    }
  }

  // =========================================================================
  // 手势检测（仅在 dispatchTouchEvent 中统一处理，避免重复逻辑）
  // =========================================================================

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchDownX = event.getX();
        touchDownY = event.getY();
        break;
      case MotionEvent.ACTION_UP:
        int dir = detectSwipe(event.getX(), event.getY());
        if (dir != 0 && callback != null) {
          if (dir > 0) callback.onSwipePrev();
          else callback.onSwipeNext();
          return true;
        }
        break;
    }
    return super.dispatchTouchEvent(event);
  }

  /**
   * 检测滑动方向。
   *
   * @return 1 = 上一页, -1 = 下一页, 0 = 无有效滑动
   */
  private int detectSwipe(float upX, float upY) {
    if (swipeThreshold <= 0) return 0;
    float dx = upX - touchDownX;
    float dy = upY - touchDownY;
    if (dx > swipeThreshold || dy > swipeThreshold) return 1;
    if (dx < -swipeThreshold || dy < -swipeThreshold) return -1;
    return 0;
  }

  // =========================================================================
  // 点击监听器（单例 + tag 传递位置，避免每次绑定创建新对象）
  // =========================================================================

  private final OnClickListener clickListener = new OnClickListener() {
    @Override
    public void onClick(View v) {
      if (callback == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return;
      if (isDelete) {
        callback.onItemDeleteClick(dataList.get(pos));
      } else {
        callback.onItemClick(dataList.get(pos));
      }
    }
  };

  private final OnLongClickListener longClickListener = new OnLongClickListener() {
    @Override
    public boolean onLongClick(View v) {
      if (callback == null) return false;
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return false;
      callback.onItemLongClick(v, dataList.get(pos));
      return true;
    }
  };

  private final OnClickListener deleteClickListener = new OnClickListener() {
    @Override
    public void onClick(View v) {
      if (callback == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return;
      callback.onItemDeleteClick(dataList.get(pos));
    }
  };

  private final OnClickListener hideClickListener = new OnClickListener() {
    @Override
    public void onClick(View v) {
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return;
      String pkg = dataList.get(pos).activityInfo.packageName;
      boolean hidden;
      if (hideAppPkg.contains(pkg)) {
        hideAppPkg.remove(pkg);
        hidden = false;
      } else {
        hideAppPkg.add(pkg);
        hidden = true;
      }
      v.setSelected(hidden);
      if (callback != null) {
        callback.onItemHideToggle(pkg, hidden);
      }
    }
  };
}
