"""Offline EGM2008 orthometric-to-WGS84 ellipsoidal height conversion."""
from functools import lru_cache
from pathlib import Path
import hashlib
import math
import numpy as np

MODEL = Path(__file__).resolve().parent.parent/'shared/geoids/egm2008-5.pgm'
MODEL_BLOB = '01f6deb7f6c67a46a4773dab67350d97fe72edf1'

@lru_cache(maxsize=1)
def grid():
    try:
        data=MODEL.read_bytes()
    except OSError as error:
        raise ValueError('EGM2008高程模型缺失，不能把地图海拔直接用作椭球高') from error
    digest=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    if digest!=MODEL_BLOB:raise ValueError('EGM2008高程模型完整性校验失败')
    # The pinned GeographicLib PGM has a documented 16-bit, north-to-south global grid.
    end=data.index(b'\n65535\n')+len(b'\n65535\n')
    header=data[:end].decode('ascii')
    if not (header.startswith('P5\n') and '# Offset -108\n' in header and '# Scale 0.003\n' in header
            and header.splitlines()[-2].split()==['4320','2161'] and len(data)-end==4320*2161*2):
        raise ValueError('EGM2008高程模型格式无效')
    return np.frombuffer(data,dtype='>u2',offset=end).reshape(2161,4320)


def undulation(latitude,longitude):
    if not all(math.isfinite(v) for v in (latitude,longitude)) or not -90<=latitude<=90 or not -180<=longitude<=180:
        raise ValueError('高程换算需要有效WGS-84经纬度')
    values=grid();y=(90-latitude)*12;x=(longitude%360)*12
    row=min(int(y),2160-1);column=int(x)%4320;dy=y-row;dx=x-int(x);right=(column+1)%4320
    # shortcut: 5-minute bilinear interpolation has max 0.478 m grid error; use a finer model for survey accuracy.
    top=float(values[row,column])*(1-dx)+float(values[row,right])*dx
    bottom=float(values[row+1,column])*(1-dx)+float(values[row+1,right])*dx
    return -108+.003*(top*(1-dy)+bottom*dy)
