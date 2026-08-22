/**
 * FloatingBallTask
 * keepAlive 任务：保证悬浮球随配置显示，配置变更时热更新。
 * 悬浮球是 UI 功能，不受后台同步总开关影响（keepAlive = true）。
 */

import { LongRunningTask } from './LongRunningTask';
import { floatingBallService } from '../services/floatingBall/FloatingBallService';
import { isFloatingBallShowing } from 'floating-ball';

class FloatingBallTask extends LongRunningTask {
  readonly name = 'floatingBall';

  async start(): Promise<void> {
    await floatingBallService.applyFromConfig();
  }

  override async onConfigChanged(): Promise<void> {
    await floatingBallService.applyFromConfig();
  }

  async stop(): Promise<void> {
    // 仅在任务被显式停止时隐藏；后台切换不会触发（keepAlive）。
    floatingBallService.stop();
  }

  isRunning(): boolean {
    return isFloatingBallShowing();
  }
}

export const floatingBallTask = new FloatingBallTask();
