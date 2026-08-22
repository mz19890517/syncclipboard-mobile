/**
 * FloatingBallService
 * 悬浮球生命周期管理：根据配置与悬浮窗权限显示/更新/隐藏悬浮球。
 * 手势动作在原生侧解析，本服务只负责把配置同步给原生模块。
 */

import {
  hideFloatingBall,
  isFloatingBallShowing,
  showFloatingBall,
  updateFloatingBallConfig,
  type FloatingBallGestureMap,
} from 'floating-ball';
import { hasOverlayPermission } from 'clipboard-overlay';
import { configService } from '../ConfigService';
import type { AppConfig } from '@/types/storage';

/** 悬浮球尺寸档位（dp） */
export const FLOATING_BALL_SIZES = [36, 48, 60] as const;
/** 悬浮球透明度档位 */
export const FLOATING_BALL_OPACITIES = [1, 0.8, 0.6] as const;

class FloatingBallService {
  /**
   * 读取当前配置并应用到原生悬浮球。
   * 未启用或无权限时确保隐藏。
   */
  async applyFromConfig(): Promise<void> {
    try {
      const config = await configService.getConfig();
      this.apply(config);
    } catch (e) {
      console.error('[FloatingBallService] Failed to apply config:', e);
    }
  }

  /** 按配置应用悬浮球（幂等） */
  apply(config: AppConfig | null): void {
    if (!config?.floatingBallEnabled || !hasOverlayPermission()) {
      hideFloatingBall();
      return;
    }
    const sizeDp = config.floatingBallSize ?? 48;
    const opacity = config.floatingBallOpacity ?? 0.8;
    const locked = config.floatingBallLocked ?? true;
    const autoHide = config.floatingBallAutoHide ?? true;
    const actions: FloatingBallGestureMap = config.floatingBallGestures ?? {};
    try {
      if (isFloatingBallShowing()) {
        updateFloatingBallConfig(sizeDp, opacity, locked, autoHide, actions);
      } else {
        showFloatingBall(sizeDp, opacity, locked, autoHide, actions);
      }
    } catch (e) {
      console.error('[FloatingBallService] Failed to show floating ball:', e);
    }
  }

  /** 隐藏悬浮球 */
  stop(): void {
    try {
      hideFloatingBall();
    } catch (e) {
      console.error('[FloatingBallService] Failed to hide floating ball:', e);
    }
  }
}

export const floatingBallService = new FloatingBallService();
