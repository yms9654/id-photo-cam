package com.yms.idphoto.camera

/**
 * 프레임마다 나오는 판정은 경계값에서 깜빡인다. 몇 프레임 연속으로 같은 결과일 때만 상태를 바꿔
 * 카운트다운이 켜졌다 꺼졌다 하는 것을 막는다. 켜질 때는 빠르게, 꺼질 때는 조금 느리게.
 */
class ReadyDebouncer(
    private val enterFrames: Int = 3,
    private val exitFrames: Int = 6,
) {
    private var good = 0
    private var bad = 0
    var ready: Boolean = false
        private set

    fun update(frameReady: Boolean): Boolean {
        if (frameReady) {
            good++
            bad = 0
        } else {
            bad++
            good = 0
        }
        if (!ready && good >= enterFrames) ready = true
        if (ready && bad >= exitFrames) ready = false
        return ready
    }

    fun reset() {
        good = 0
        bad = 0
        ready = false
    }
}
