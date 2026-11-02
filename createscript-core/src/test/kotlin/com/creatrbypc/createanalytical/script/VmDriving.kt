package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.vm.CsVm

// Scheduler を通さずに VM を進めるテスト用の手順。回収中なら回収（Phase 3.5）を、そうでなければ命令（Phase 3）を最大 budget だけ進める
internal fun CsVm.advance(budget: Int): Int = if (isCollecting) collectGarbage(budget) else run(budget)
