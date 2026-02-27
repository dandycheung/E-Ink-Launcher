package cn.modificator.launcher.widgets;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
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

/**
 * {@link EInkLauncherView} 的数据适配器，负责 ViewHolder 管理、数据绑定和交互事件。
 * <p>
 * 类似 RecyclerView.Adapter 的职责划分：
 * <ul>
 *   <li>创建和管理 {@link ItemViewHolder}</li>
 *   <li>将应用数据绑定到 View（图标、标签、自定义图标替换）</li>
 *   <li>处理点击/长按/卸载/隐藏等交互事件</li>
 *   <li>管理"管理模式"（删除/隐藏）的 UI 状态</li>
 * </ul>
 */
public class LauncherAdapter {

  // =========================================================================
  // 回调接口
  // =========================================================================

  /** 宿主实现此接口以处理用户交互事件。 */
  public interface Callback {
    /** 普通模式下点击应用 */
    void onItemClick(ResolveInfo info);

    /** 长按应用 */
    void onItemLongClick(View anchor, ResolveInfo info);

    /** 管理模式下点击卸载 */
    void onItemDeleteClick(ResolveInfo info);

    /** 管理模式下切换隐藏状态 */
    void onItemHideToggle(String packageName, boolean hidden);
  }

  // =========================================================================
  // ViewHolder
  // =========================================================================

  static class ItemViewHolder {
    final View itemView;
    final ImageView appImage;
    final ObserverFontTextView appName;
    final View menuContainer;
    final View menuDelete;
    final View menuHide;

    ItemViewHolder(View itemView) {
      this.itemView = itemView;
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

  private final PackageManager packageManager;
  private final ObservableFloat fontSizeObservable = new ObservableFloat();
  private final List<ResolveInfo> dataList = new ArrayList<>();
  private final List<ItemViewHolder> holders = new ArrayList<>();
  private final Set<String> hideAppPkg = new HashSet<>();

  private Callback callback;
  private IconCache iconCache;
  private EInkLauncherView attachedView;

  private float fontSize = 14;
  private int appNameLines = Integer.MAX_VALUE;
  private boolean isDelete = false;

  // =========================================================================
  // 构造器
  // =========================================================================

  public LauncherAdapter(PackageManager pm) {
    this.packageManager = pm;
  }

  // =========================================================================
  // View 绑定（包级可见，由 EInkLauncherView 调用）
  // =========================================================================

  void attachView(EInkLauncherView view) {
    this.attachedView = view;
  }

  void detachView() {
    this.attachedView = null;
  }

  // =========================================================================
  // 外部依赖
  // =========================================================================

  public void setCallback(Callback callback) {
    this.callback = callback;
  }

  public void setIconCache(IconCache iconCache) {
    this.iconCache = iconCache;
  }

  // =========================================================================
  // 显示参数
  // =========================================================================

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

  // =========================================================================
  // 管理模式
  // =========================================================================

  public void setDelete(boolean delete) {
    this.isDelete = delete;
    updateAllDeleteState();
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

  /** 设置当前页要显示的应用列表，自动触发重新绑定 */
  public void setAppList(List<ResolveInfo> appList) {
    dataList.clear();
    dataList.addAll(appList);
    if (attachedView != null) {
      attachedView.rebind();
    }
  }

  /** 仅刷新显示（自定义图标变更后调用） */
  public void refreshDisplay() {
    if (attachedView != null) {
      attachedView.rebind();
    }
  }

  public int getItemCount() {
    return dataList.size();
  }

  // =========================================================================
  // ViewHolder 管理（包级可见，由 EInkLauncherView 调用）
  // =========================================================================

  List<ItemViewHolder> getHolders() {
    return holders;
  }

  int getHolderCount() {
    return holders.size();
  }

  /** 创建一个新的 ViewHolder，注册字体观察者，初始化文字参数 */
  ItemViewHolder createViewHolder(ViewGroup parent) {
    View itemView = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.launcher_item, parent, false);
    ItemViewHolder holder = new ItemViewHolder(itemView);
    holders.add(holder);

    fontSizeObservable.addObserver((Observer) holder.appName);
    holder.appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
    holder.appName.setMinLines(appNameLines == 2 ? appNameLines : 0);
    holder.appName.setMaxLines(appNameLines);
    holder.appName.setEllipsize(TextUtils.TruncateAt.END);

    return holder;
  }

  /** 清除所有 ViewHolder 并取消字体观察 */
  void clearHolders() {
    fontSizeObservable.deleteObservers();
    holders.clear();
  }

  // =========================================================================
  // 数据绑定（包级可见，由 EInkLauncherView.rebind 调用）
  // =========================================================================

  /** 绑定所有 holder 的数据 */
  void bindAll() {
    Map<String, File> customIcons = iconCache != null ? iconCache.getCustomIconMap() : null;
    WifiControl.bind(null, customIcons);

    for (int i = 0; i < holders.size(); i++) {
      ItemViewHolder holder = holders.get(i);
      if (i < dataList.size()) {
        bindViewHolder(holder, i, customIcons);
      } else {
        clearViewHolder(holder);
      }
    }
    updateAllDeleteState();
  }

  private void bindViewHolder(ItemViewHolder holder, int position,
                               Map<String, File> customIcons) {
    ResolveInfo info = dataList.get(position);
    String pkg = info.activityInfo.packageName;

    // —— 图标 & 标签 ——
    if (AppDataCenter.WIFI_PACKAGE_NAME.equals(pkg)) {
      WifiControl.bind(holder.itemView, customIcons);
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
    holder.itemView.setTag(position);
    holder.itemView.setOnClickListener(clickListener);
    holder.itemView.setOnLongClickListener(longClickListener);
    holder.menuDelete.setTag(position);
    holder.menuDelete.setOnClickListener(deleteClickListener);
    holder.menuHide.setTag(position);
    holder.menuHide.setOnClickListener(hideClickListener);

    holder.itemView.setVisibility(View.VISIBLE);
    holder.itemView.setAlpha(1);
  }

  private void clearViewHolder(ItemViewHolder holder) {
    holder.appName.setText("");
    holder.appImage.setImageDrawable(null);
    holder.itemView.setOnClickListener(null);
    holder.itemView.setOnLongClickListener(null);
    holder.menuDelete.setOnClickListener(null);
    holder.menuHide.setOnClickListener(null);
    holder.itemView.setAlpha(0);
  }

  // =========================================================================
  // 图标加载
  // =========================================================================

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

  // =========================================================================
  // 管理模式 UI
  // =========================================================================

  private void updateAllDeleteState() {
    for (int i = 0; i < holders.size() && i < dataList.size(); i++) {
      ItemViewHolder holder = holders.get(i);

      if (!isDelete) {
        holder.menuContainer.setVisibility(View.GONE);
        continue;
      }

      holder.menuContainer.setVisibility(View.VISIBLE);
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

      holder.menuDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
      holder.menuHide.setSelected(hideAppPkg.contains(pkg));
    }
  }

  // =========================================================================
  // 点击监听器（单例 + tag 传递位置，避免每次绑定创建新对象）
  // =========================================================================

  private final View.OnClickListener clickListener = new View.OnClickListener() {
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

  private final View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
    @Override
    public boolean onLongClick(View v) {
      if (callback == null) return false;
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return false;
      callback.onItemLongClick(v, dataList.get(pos));
      return true;
    }
  };

  private final View.OnClickListener deleteClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      if (callback == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataList.size()) return;
      callback.onItemDeleteClick(dataList.get(pos));
    }
  };

  private final View.OnClickListener hideClickListener = new View.OnClickListener() {
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
