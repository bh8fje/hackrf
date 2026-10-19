"""Generated channel geometry, not measurements from a phone receiver."""
import re,math

def scene_satellites(text):
    values={}
    for prn,az,el in re.findall(r'^\s*(\d{2})\s+(-?\d+\.\d+)\s+(-?\d+\.\d+)\s+\d+\.\d+\s+\d+\.\d+\s*$',text,re.M):
        p,a,e=int(prn),float(az),float(el)
        if 1<=p<=32 and all(map(math.isfinite,(a,e))) and 0<=a<=360 and 0<=e<=90:
            values[p]=f'G{p:02d} 方位{a:.1f}° 仰角{e:.1f}°'
    return [values[p] for p in sorted(values)]
