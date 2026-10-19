package local.hackrf.controller;

/** Build checks these tables against shared/signal-environments.json. */
final class SignalProfile {
    static final String MIXED_SYSTEM="mixed";
    static final boolean MIXED_ENABLED=true;
    static void requireMixedEnabled() throws java.io.IOException {if(!MIXED_ENABLED)throw new java.io.IOException("GPS＋北斗混合输出仍在验收，尚未开放手机发射");}
    static final String[] IDS={"open","trees","building-edge","office"};
    static final String[] LABELS={"开阔园区 · 轻微波动","树荫步道 · 轻度遮挡","道路建筑旁 · 局部遮挡","办公室近窗 · 稳定轻遮挡"};
    static final String[] ROUTES={"stroll","office","roadside","wander","park","stationary","circle","line"};
    static final int[] PROFILES={1,3,2,0,1,3,0,2};
    static int forRoute(String route){for(int i=0;i<ROUTES.length;i++)if(ROUTES[i].equals(route))return PROFILES[i];throw new IllegalArgumentException("不支持的运动方式");}
    static int index(String id){for(int i=0;i<IDS.length;i++)if(IDS[i].equals(id))return i;throw new IllegalArgumentException("不支持的信号环境");}
}
