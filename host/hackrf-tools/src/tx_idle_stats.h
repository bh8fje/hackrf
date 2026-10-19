#ifndef TX_IDLE_STATS_H
#define TX_IDLE_STATS_H
#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint32_t m0, m4, shortfalls, longest;
    unsigned int stable_reads;
} tx_idle_stats;

/* Firmware may roll back an ongoing shutdown shortfall after the OFF ACK. */
static bool tx_idle_stats_update(tx_idle_stats* previous, bool read_ok,
    uint32_t active, uint32_t requested, uint32_t pending, uint32_t error,
    uint32_t m0, uint32_t m4, uint32_t shortfalls, uint32_t longest)
{
    if (!read_ok || active != 0 || requested != 0 || pending != 0 || error != 0) {
        previous->stable_reads = 0;
        return false;
    }
    if (previous->stable_reads != 0 && previous->m0 == m0 && previous->m4 == m4 &&
        previous->shortfalls == shortfalls && previous->longest == longest) {
        previous->stable_reads++;
    } else {
        previous->stable_reads = 1;
    }
    previous->m0 = m0;
    previous->m4 = m4;
    previous->shortfalls = shortfalls;
    previous->longest = longest;
    return previous->stable_reads >= 3;
}
#endif
