"""Mac preparation reserve matches Android; includes Mac receiver validation time."""
import json
import math
from pathlib import Path

STATE=Path(__file__).resolve().parent/'output/preparation-mac.json'
BDS_STATE=STATE.with_name('preparation-bds-mac.json')


def lead_seconds(duration, rate=.18):
    if not 60<=duration<=600:raise ValueError('Invalid duration')
    if not math.isfinite(rate) or rate<=0:rate=.18
    seconds=duration*rate/1.6
    return math.ceil(min(210,seconds+max(5,min(30,seconds*.1))))


def bds_lead_seconds(duration,rate=2.4):
    if not 90<=duration<=600:raise ValueError('Invalid BDS duration')
    if not math.isfinite(rate) or rate<=0:rate=2.4
    seconds=duration*rate/1.6
    return math.ceil(min(600,seconds+max(5,min(30,seconds*.2))))


def previous_rate(state=None):
    state=state or STATE
    try:
        rate=float(json.loads(state.read_text())['secondsPerSceneSecond'])
        return rate if math.isfinite(rate) and rate>0 else .18
    except (OSError,ValueError,TypeError,KeyError):return .18


def record_duration(duration, elapsed,state=None):
    state=state or STATE
    if not 60<=duration<=600 or not math.isfinite(elapsed) or elapsed<0:raise ValueError('Invalid preparation timing')
    rate=elapsed/duration*1.6 if elapsed>0 else previous_rate(state)
    state.parent.mkdir(parents=True,exist_ok=True)
    temporary=state.with_suffix('.pending')
    temporary.write_text(json.dumps({'secondsPerSceneSecond':rate}))
    temporary.replace(state)
