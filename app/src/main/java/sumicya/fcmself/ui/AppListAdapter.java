package sumicya.fcmself.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sumicya.fcmself.R;
import sumicya.fcmself.data.IconLoader;
import sumicya.fcmself.model.AppInfo;

/** The application grid: stable ids, payload-only rebinds and asynchronous icons. */
public final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private static final Object PAYLOAD_STATUS = new Object();
    private static final Object PAYLOAD_ICON = new Object();
    private static final Object PAYLOAD_SHAPE = new Object();

    /** Everything the grid needs from the screen that owns it. */
    public interface Host {
        /** False while the remote configuration has not been read yet. */
        boolean isAllowListLoaded();

        int spanCount();

        /** A row the user asked to toggle; the host persists and then flips the entry. */
        void onAppToggleRequested(AppInfo info, int position);

        /** A row without any detected FCM entry point was tapped. */
        void onUnsupportedApp(AppInfo info);
    }

    private final List<AppInfo> items = new ArrayList<>();
    /** Current filtered-list index by package; makes asynchronous icon completion O(1). */
    private final Map<String, Integer> positions = new HashMap<>();
    /** Last status actually submitted to RecyclerView, independent of mutable AppInfo objects. */
    private final Map<String, Integer> renderedStatus = new HashMap<>();

    private final Context context;
    private final PackageManager packageManager;
    private final AppTheme theme;
    private final Host host;
    private final Drawable defaultIcon;
    private final float cornerRadius;

    public AppListAdapter(Context context, AppTheme theme, Host host) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
        this.theme = theme;
        this.host = host;
        this.defaultIcon = this.packageManager.getDefaultActivityIcon();
        this.cornerRadius = theme.dpF(16);
        setHasStableIds(true);
    }

    public static final class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView appView;
        final ImageView icon;
        final TextView name;
        final TextView appPackage;
        final ImageView status;

        ViewHolder(View view) {
            super(view);
            appView = (MaterialCardView) view;
            icon = view.findViewById(R.id.icon);
            name = view.findViewById(R.id.name);
            appPackage = view.findViewById(R.id.app_pkg);
            status = view.findViewById(R.id.status);
        }
    }

    public void replaceList(List<AppInfo> list) {
        // Most refreshes only update allow-state while retaining the same filtered packages.
        // Skip DiffUtil's O(n) comparison in that common path and issue status-only payloads.
        if (hasSameItemOrder(list)) {
            for (int i = 0; i < list.size(); i++) {
                AppInfo previous = items.get(i);
                AppInfo current = list.get(i);
                int oldStatus = renderedStatus.getOrDefault(
                        previous.packageName, statusState(previous));
                int newStatus = statusState(current);
                items.set(i, current);
                renderedStatus.put(current.packageName, newStatus);
                if (!previous.name.equals(current.name)
                        || previous.includeFcm != current.includeFcm) {
                    notifyItemChanged(i);
                } else if (oldStatus != newStatus) {
                    notifyItemChanged(i, PAYLOAD_STATUS);
                }
            }
            return;
        }

        // AppInfo is intentionally mutable. Snapshot the old list and rendered statuses before
        // replacing it, otherwise DiffUtil can compare an already-mutated object with itself and
        // miss an undo/config status change.
        final List<AppInfo> oldList = new ArrayList<>(items);
        final Map<String, Integer> oldStatuses = new HashMap<>(renderedStatus);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return list.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldList.get(oldItemPosition).packageName
                        .equals(list.get(newItemPosition).packageName);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                AppInfo oldItem = oldList.get(oldItemPosition);
                AppInfo newItem = list.get(newItemPosition);
                return oldItem.name.equals(newItem.name)
                        && oldItem.includeFcm == newItem.includeFcm
                        && oldStatuses.getOrDefault(oldItem.packageName, statusState(oldItem))
                        == statusState(newItem);
            }

            @Override
            public Object getChangePayload(int oldItemPosition, int newItemPosition) {
                AppInfo oldItem = oldList.get(oldItemPosition);
                AppInfo newItem = list.get(newItemPosition);
                if (oldItem.name.equals(newItem.name)
                        && oldItem.includeFcm == newItem.includeFcm) {
                    return PAYLOAD_STATUS;
                }
                return null;
            }
        }, true);
        items.clear();
        items.addAll(list);
        rebuildSubmittedState();
        result.dispatchUpdatesTo(this);
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size(), PAYLOAD_SHAPE);
        }
    }

    /** Repaints one row after the host flipped its allow-state. */
    public void notifyStatusChanged(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        AppInfo item = items.get(position);
        renderedStatus.put(item.packageName, statusState(item));
        notifyItemChanged(position, PAYLOAD_STATUS);
    }

    private boolean hasSameItemOrder(List<AppInfo> list) {
        if (items.size() != list.size()) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!items.get(i).packageName.equals(list.get(i).packageName)) {
                return false;
            }
        }
        return true;
    }

    private int statusState(AppInfo info) {
        return info.statusState(host.isAllowListLoaded());
    }

    private void rebuildSubmittedState() {
        positions.clear();
        renderedStatus.clear();
        for (int i = 0; i < items.size(); i++) {
            AppInfo item = items.get(i);
            positions.put(item.packageName, i);
            renderedStatus.put(item.packageName, statusState(item));
        }
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stableId;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.app_item, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        holder.appView.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            AppInfo info = items.get(position);
            if (!info.includeFcm && !info.isAllow) {
                host.onUnsupportedApp(info);
                return;
            }
            host.onAppToggleRequested(info, position);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo info = items.get(position);
        // RecyclerView holders must never inherit an interrupted press-scale animation.
        holder.appView.animate().cancel();
        holder.appView.setScaleX(1f);
        holder.appView.setScaleY(1f);
        holder.name.setText(info.name);
        holder.appPackage.setText(info.packageName);
        bindCardShape(holder, position);
        bindStatus(holder, info);
        bindIcon(holder, info);
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        AppInfo info = items.get(position);
        holder.icon.setTag(info.packageName);
        for (Object payload : payloads) {
            if (payload == PAYLOAD_ICON) {
                Drawable icon = IconLoader.cached(info.packageName);
                if (icon != null && info.packageName.equals(holder.icon.getTag())) {
                    holder.icon.setImageDrawable(icon);
                }
            } else if (payload == PAYLOAD_STATUS) {
                bindStatus(holder, info);
            } else if (payload == PAYLOAD_SHAPE) {
                bindCardShape(holder, position);
            }
        }
    }

    private void bindIcon(ViewHolder holder, AppInfo info) {
        // Tag guards against a recycled view showing the wrong icon.
        holder.icon.setTag(info.packageName);
        Drawable cached = IconLoader.cached(info.packageName);
        if (cached != null) {
            holder.icon.setImageDrawable(cached);
            return;
        }
        holder.icon.setImageDrawable(defaultIcon);
        final WeakReference<AppListAdapter> ref = new WeakReference<>(this);
        IconLoader.load(packageManager, info, defaultIcon, packageName -> {
            AppListAdapter adapter = ref.get();
            if (adapter != null) {
                adapter.onIconReady(packageName);
            }
        });
    }

    private void onIconReady(String packageName) {
        Integer position = positions.get(packageName);
        if (position != null
                && position >= 0
                && position < items.size()
                && packageName.equals(items.get(position).packageName)) {
            notifyItemChanged(position, PAYLOAD_ICON);
        }
    }

    private void bindCardShape(ViewHolder holder, int position) {
        int itemCount = items.size();
        if (itemCount == 0 || position < 0 || position >= itemCount) {
            return;
        }
        int spanCount = Math.max(1, host.spanCount());
        int last = itemCount - 1;
        int topRight = Math.min(spanCount - 1, last);
        int bottomLeft = last - (last % spanCount);
        ShapeAppearanceModel.Builder shape = holder.appView.getShapeAppearanceModel()
                .toBuilder()
                .setAllCornerSizes(0f);
        if (position == 0) {
            shape.setTopLeftCornerSize(cornerRadius);
        }
        if (position == topRight) {
            shape.setTopRightCornerSize(cornerRadius);
        }
        if (position == bottomLeft) {
            shape.setBottomLeftCornerSize(cornerRadius);
        }
        if (position == last) {
            shape.setBottomRightCornerSize(cornerRadius);
        }
        holder.appView.setShapeAppearanceModel(shape.build());
    }

    private void bindStatus(ViewHolder holder, AppInfo info) {
        bindCardAppearance(holder, info);
        // Vector marks are tinted so the icon follows the theme instead of being re-drawn as an
        // emoji glyph by the OEM font on the device. Manually allowed apps keep a check even when
        // heuristic component scanning did not identify FCM; otherwise the FCM filter and the tile
        // would disagree about the same allow-list entry.
        final int description;
        if (info.isAllow) {
            holder.status.setImageResource(R.drawable.ic_status_check);
            holder.status.setImageTintList(theme.tintOnPrimaryContainer);
            description = R.string.app_status_enabled_desc;
        } else if (info.includeFcm && host.isAllowListLoaded()) {
            holder.status.setImageResource(R.drawable.ic_status_close);
            holder.status.setImageTintList(theme.tintOnErrorContainer);
            description = R.string.app_status_disabled_desc;
        } else {
            // Config not read yet (service still binding after a cold start), or no FCM component
            // was detected: show the neutral marker instead of a false cross.
            holder.status.setImageResource(R.drawable.ic_status_dash);
            holder.status.setImageTintList(theme.tintNeutral);
            description = info.includeFcm
                    ? R.string.app_status_pending_desc
                    : R.string.app_status_unsupported_desc;
        }
        holder.appView.setContentDescription(context.getString(description, info.name));
    }

    private void bindCardAppearance(ViewHolder holder, AppInfo info) {
        if (info.isAllow) {
            theme.applyPrimarySurface(holder.appView, true, theme.surfaceContainerLow);
            holder.name.setTextColor(theme.onPrimaryContainer);
            holder.appPackage.setTextColor(theme.onPrimaryContainer);
        } else if (info.includeFcm && host.isAllowListLoaded()) {
            theme.applyPrimarySurface(holder.appView, false, theme.errorContainer);
            holder.name.setTextColor(theme.onErrorContainer);
            holder.appPackage.setTextColor(theme.onErrorContainer);
        } else {
            theme.applyPrimarySurface(holder.appView, false, theme.surfaceContainerLow);
            holder.name.setTextColor(theme.onSurface);
            holder.appPackage.setTextColor(theme.onSurfaceVariant);
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.appView.animate().cancel();
        holder.appView.setScaleX(1f);
        holder.appView.setScaleY(1f);
        holder.icon.setTag(null);
        holder.icon.setImageDrawable(defaultIcon);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
