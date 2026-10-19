#include <assert.h>
#include "../src/tx_idle_stats.h"
int main(void)
{
    tx_idle_stats s = {0};
    assert(!tx_idle_stats_update(&s,true,4,0,0,0,100,100,1,20));
    assert(!tx_idle_stats_update(&s,true,0,0,1,0,100,100,1,20));
    assert(!tx_idle_stats_update(&s,false,0,0,0,0,100,100,1,20));
    assert(!tx_idle_stats_update(&s,true,0,0,0,1,100,100,1,20));
    assert(!tx_idle_stats_update(&s,true,0,0,0,0,100,100,1,20));
    assert(!tx_idle_stats_update(&s,true,0,0,0,0,100,100,0,0));
    assert(!tx_idle_stats_update(&s,true,0,0,0,0,100,100,0,0));
    assert(tx_idle_stats_update(&s,true,0,0,0,0,100,100,0,0));
    assert(!tx_idle_stats_update(&s,true,0,0,0,0,101,101,1,20));
    assert(!tx_idle_stats_update(&s,true,0,0,0,0,101,101,1,20));
    assert(tx_idle_stats_update(&s,true,0,0,0,0,101,101,1,20));
    assert(s.shortfalls == 1); /* A completed in-window shortfall is never subtracted. */
    return 0;
}
