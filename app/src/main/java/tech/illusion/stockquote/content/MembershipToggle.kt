package tech.illusion.stockquote.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.stockquote.data.Membership

/** 四态钮直径。IconButton 自带的命中区不小于这个尺寸，够头显射线点。 */
private val ToggleSize = 40.dp

/**
 * 加/删自选的唯一入口：一颗按钮承担全部四种状态（契约 v5 §5.4）。
 *
 * 用 `IconButton` 而不是自己拼 `Box + clickable`，是为了白拿 SDK 的 hover / 按压反馈 / 手柄震动
 * （`BasicIconButton` 内部已经接好）。**必须显式传 `shape = CircleShape`**：`IconButtonDefaults.DefaultShape`
 * 是 internal 引用不到，而自定义 `ButtonSize` 会让默认圆角悄悄丢失。
 *
 * 四态一律留在琥珀色系里，只有形态在变（空心＋ → 转环 → 实心✓）。旧稿用灰环表示"提交中"被否掉了：
 * 环太细 + 色相脱离琥珀，读起来像"这颗按钮失效了"。
 */
@Composable
internal fun MembershipToggle(
    membership: Membership,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (membership) {
        Membership.NotAdded -> ToggleIdleSlot
        Membership.Adding -> ToggleBusySlot
        Membership.Added -> PicoTheme.colorScheme.interaction
        Membership.Failed -> ToggleFailSlot
    }
    val content = when (membership) {
        Membership.NotAdded -> PicoTheme.colorScheme.interaction
        Membership.Adding -> PicoTheme.colorScheme.interaction
        // 琥珀实心底上要深色字。不能靠 colorScheme.labelPrimaryLight——那个角色保持白色，
        // 因为它同时是所有默认 IconButton 的 contentColor（IconButton.kt:194）。
        Membership.Added -> PicoTheme.colorScheme.fillPrimary
        Membership.Failed -> PicoTheme.colorScheme.error
    }

    IconButton(
        // Adding 期间点击是空操作，而不是 enabled = false：置灰会把琥珀化的槽色一起冲掉，
        // "正在提交"就又变回"这颗坏了"。
        onClick = {
            when (membership) {
                Membership.NotAdded -> onAdd()
                Membership.Added -> onRemove()
                Membership.Failed -> onRetry()
                Membership.Adding -> Unit
            }
        },
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = container, contentColor = content),
        size = IconButtonDefaults.iconButtonSize(ToggleSize),
        shape = CircleShape,
    ) {
        when (membership) {
            Membership.NotAdded -> Text("＋")
            Membership.Adding -> CircularProgressIndicator(
                color = PicoTheme.colorScheme.interaction,
                progressSize = CircularProgressDefaults.circleProgressSize(18.dp),
            )
            Membership.Added -> Text("✓")
            Membership.Failed -> Text("!")
        }
    }
}

/**
 * 移除/添加后的 5 秒撤销条（契约 v5 §6），取代了原先的删除确认 `AlertDialog`。
 *
 * ⚠️ 调用方必须用 `key(generation) { UndoBar(...) }` 包一层。SDK 的可点组件会保留**首次**传入的
 * lambda（`pico-sdk-compose-pitfalls` 陷阱 1），撤销条留在 composition 里而内容换成下一条时，
 * 「撤销」会去执行上一条的动作。换 key 强制换节点是唯一可靠的解法。
 */
@Composable
internal fun UndoBar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PicoTheme.colorScheme.fillSecondary, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = PicoTheme.typography.labelMedium,
            color = PicoTheme.colorScheme.labelSecondary,
        )
        Link(onClick = onUndo) {
            Text(
                text = "撤销",
                style = PicoTheme.typography.labelMedium,
                color = PicoTheme.colorScheme.interaction,
            )
        }
    }
}
