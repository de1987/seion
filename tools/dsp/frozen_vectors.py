#!/usr/bin/env python3
"""Frozen-number vectors for the Seion DSP chain constants.

Generated once from the Python reference of each stage and stored as small JSON (numbers, not audio) next to the JVM
tests (app/src/test/resources/dsp/frozen-*.json). Property tests catch broken primitives; these catch a wrong
constant (margin sign, slope clamp, beta formula, kx, the -3 dB/oct roll-off, the 0.15 even term, limiter timing).

  1 frozen-bwe.json      three STFT frames of synthetic spectra (source slopes -6, -24 and +6 dB/oct, so both slope
                         clamp bounds are exercised) -> s, b, L_fc, beta, T_k, |Delta_k| (RenovaPure's
                         regression, target and fill rules with its ceiling, taper and source band)
  2 frozen-detector.json one window PSD -> per-window edge (lossy and lossless rules), provisional fc, P10 fc
  3 frozen-limiter.json  a 2000-sample gain curve for a step over (the streaming limiter, sample for sample)
  4 frozen-crystalx.json the 13 linear stage gains of CrystalX+ at 10 frequencies (scipy butter/sosfreqz and a
                         bilinear-transform shelf written here, independent of the Kotlin filter design)

Run with a Python that has numpy and scipy:  python3 tools/dsp/frozen_vectors.py
"""
import json, os, math
import numpy as np
from scipy.signal import butter, sosfreqz, freqz

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "test", "resources", "dsp")


def dump(name, obj):
    path = os.path.join(OUT, name)
    with open(path, "w") as f:
        json.dump(obj, f, separators=(",", ":"))
    print(name, os.path.getsize(path), "bytes")


def xorshift32(seed):
    s = seed & 0xFFFFFFFF
    while True:
        s ^= (s << 13) & 0xFFFFFFFF
        s ^= s >> 17
        s ^= (s << 5) & 0xFFFFFFFF
        yield s


# ------------------------------------------------------------------ 1. BWE frame
def bwe_frame(source_slope, floor_db, seed):
    """One frame: a source band falling at [source_slope] dB/oct up to fc, a codec floor of [floor_db] above it.

    -6 dB/oct leaves clamp(s, -18, 0) inactive; -24 and +6 dB/oct drive it to each bound, so the clamp limits and the
    unclamped slope in L_fc are pinned. X is rounded through float32 (the Kotlin input), so the reference regression
    sees exactly the numbers the port sees and every tolerance can be 1e-4 dB.
    """
    fs, n = 44100, 512
    df = fs / n
    fc = 16000.0
    nb = n // 2 + 1
    f = np.arange(nb) * df
    rng = xorshift32(seed)
    mag = np.zeros(nb)
    ph = np.zeros(nb)
    for k in range(nb):
        r1 = next(rng) / 2**32
        r2 = next(rng) / 2**32
        if 0 < f[k] < fc:
            level_db = 30 + source_slope * math.log2(max(f[k], 50) / 1000) + (r1 - 0.5) * 6
        else:
            level_db = floor_db + (r1 - 0.5) * 6
        mag[k] = 10 ** (level_db / 20)
        ph[k] = 2 * math.pi * r2
    X = mag * np.exp(1j * ph)
    X[0] = X[0].real
    X[-1] = X[-1].real
    X = X.astype(np.complex64).astype(np.complex128)
    margin, extra = 1.5, -3.0
    kc = int(round(fc / df))
    kmax = int(math.floor(min(21000.0, fs / 2 - 1050.0) / df))
    W = min(kmax - kc, kc - int(round(fc / 2 / df)))
    W = max(4, (W // 4) * 4)
    ks = kc - W
    gen = np.arange(kc, kmax + 1)
    src = ks + ((gen - kc) % W)
    kx = int(math.floor(1500.0 / df))
    kt = int(math.floor(1000.0 / df))
    taper = np.ones(len(gen))
    taper[-kt:] = 0.5 + 0.5 * np.cos(np.pi * np.arange(kt) / kt)
    reg = (f >= fc / 2) & (f <= fc * 2 ** (-1 / 8))
    P = np.abs(X) ** 2 + 1e-14
    L = 10 * np.log10(P)
    lf = np.log2(f[reg])
    A = np.vstack([lf, np.ones_like(lf)]).T
    s, b = np.linalg.lstsq(A, L[reg], rcond=None)[0]
    lfc = s * math.log2(fc) + b
    sc = min(0.0, max(-18.0, s))
    tau = lfc + (sc + extra) * np.log2(f[gen] / fc) - margin
    T = 10 ** (tau / 20) * taper              # first frame after a flush: env = tau
    S = X[ks:kc]                              # the source band, W bins
    Sm = np.abs(S) + 1e-9
    k = min(9, W - (1 - W % 2))
    E = np.sqrt(np.convolve(Sm ** 2, np.ones(k) / k, mode="same")) + 1e-9
    F = S / E
    sfm = np.exp(np.mean(np.log(Sm ** 2))) / np.mean(Sm ** 2)
    beta = min(0.85, max(0.25, 0.25 + 0.6 * sfm))
    rng2 = xorshift32(12345)
    theta = np.array([2 * math.pi * (next(rng2) & 1023) / 1024 for _ in range(W)])
    M = math.sqrt(1 - beta) * F + math.sqrt(beta) * np.abs(F) * np.exp(1j * theta)
    fill = np.sqrt(np.maximum(T ** 2 - np.abs(X[gen]) ** 2, 0))
    delta_gen = M[src - ks] * fill
    xi = np.arange(kx)
    xbins = kc - kx + xi
    tx = 10 ** ((lfc + sc * np.log2(f[xbins] / fc) - margin) / 20)
    wx = 0.5 - 0.5 * np.cos(np.pi * np.clip((xi + 0.5) / (2 * kx / 3), 0, 1))
    Sx = X[ks - kx + xi]
    delta_x = Sx / (np.abs(Sx) + 1e-9) * np.sqrt(np.maximum(tx ** 2 - np.abs(X[xbins]) ** 2, 0)) * wx
    return {
        "sourceSlopeDbPerOct": source_slope, "floorDb": floor_db,
        "fs": fs, "n": n, "fc": fc, "rngSeed": 12345,
        "re": [float(v) for v in X.real], "im": [float(v) for v in X.imag],
        "kc": kc, "kmax": kmax, "W": int(W), "kx": kx,
        "slope": float(s), "intercept": float(b), "levelAtFc": float(lfc), "beta": float(beta),
        "targetDb": [float(20 * np.log10(v + 1e-30)) for v in T],
        "deltaCrossoverDb": [float(20 * np.log10(abs(v) + 1e-30)) for v in delta_x],
        "deltaGenDb": [float(20 * np.log10(abs(v) + 1e-30)) for v in delta_gen],
        # condition of each fill sqrt(t^2 - |X|^2): t^2 / (t^2 - |X|^2) (0 where nothing is filled). A bin close to
        # cancellation amplifies a last-ulp difference of t, so the test scales its 1e-4 dB tolerance by this.
        "deltaCrossoverCond": cond(tx, X[xbins]),
        "deltaGenCond": cond(T, X[gen]),
    }


def cond(t, x):
    t2, p = t ** 2, np.abs(x) ** 2
    return [float(a / (a - b)) if a > b else 0.0 for a, b in zip(t2, p)]


def bwe_frames():
    frames = [bwe_frame(-6.0, -45.0, 777),     # s inside (-18, 0): the clamp is inactive
              bwe_frame(-24.0, -120.0, 778),   # s < -18: clamped to -18 for the targets, L_fc from the raw s
              bwe_frame(6.0, -45.0, 779)]      # s > 0: clamped to 0 (no rising treble is ever invented)
    for fr in frames:
        print("  bwe source %+.0f dB/oct: s = %.3f" % (fr["sourceSlopeDbPerOct"], fr["slope"]))
    dump("frozen-bwe.json", {"frames": frames})


# ------------------------------------------------------------------ 2. detector window
def edge(f, Ls, L, fs, wall_db, floor_hz):
    nyq = fs / 2
    df = f[1]
    ref = np.median(L[(f >= 1000) & (f < 4000)])
    if ref < -110:
        return None
    top = 0.94 * nyq
    ok = np.zeros(len(f), bool)
    for i in range(int(floor_hz / df), int(top / df)):
        fc = f[i]
        below = np.median(Ls[(f >= fc - 1200) & (f < fc - 300)])
        abv = Ls[(f > fc + 300) & (f <= min(fc + 1500, nyq * 0.99))]
        if len(abv) < 4:
            continue
        steep = Ls[max(0, i - int(300 / df))] - Ls[min(len(f) - 1, i + int(300 / df))]
        ok[i] = (below - np.percentile(abv, 90) >= wall_db) and (steep >= 20) and (np.median(abv) - ref <= -35)
        if ok[i]:
            tail = Ls[(f > fc + 300) & (f <= 0.985 * nyq)]
            ok[i] = len(tail) > 0 and (below - np.percentile(tail, 95) >= wall_db - 5)
    idx = np.flatnonzero(ok)
    if len(idx) == 0:
        return None
    i0 = idx[0]
    plateau = np.median(Ls[(f >= f[i0] - 2000) & (f < f[i0] - 400)])
    j = np.searchsorted(f, f[i0] - 1200)
    while j < len(f) - 1 and Ls[j] > plateau - 6:
        j += 1
    return float(f[j])


def detector():
    fs, nfft = 44100, 4096
    f = np.arange(nfft // 2 + 1) * fs / nfft
    rng = xorshift32(4242)
    L = np.zeros(len(f))
    for k in range(len(f)):
        r = next(rng) / 2**32
        base = -60 - 4.0 * math.log2(max(f[k], 100) / 1000)
        if f[k] > 16200:
            base -= 62
        elif f[k] > 15800:
            base -= 62 * (f[k] - 15800) / 400
        L[k] = base + (r - 0.5) * 4
    P = np.array([float("%.6g" % v) for v in 10 ** (L / 10)])   # stored inputs are exactly what is computed on
    Lr = 10 * np.log10(P + 1e-20)
    kk = max(1, int(150 / f[1]))
    Ls = np.convolve(Lr, np.ones(kk) / kk, mode="same")
    e_lossy = edge(f, Ls, Lr, fs, 25, 7000)
    e_lossless = edge(f, Ls, Lr, fs, 40, 13000)
    e_prov_lossy = edge(f, Ls, Lr, fs, 35, 7000)
    edges = [15890.0, 16020.0, 15760.0, 16110.0, 15930.0, 16050.0, 15800.0]
    dump("frozen-detector.json", {
        "fs": fs, "nfft": nfft, "smoothBins": kk, "psd": [float(v) for v in P],
        "edgeLossy": e_lossy, "edgeLossless": e_lossless, "edgeProvisionalLossy": e_prov_lossy,
        "provisionalFc": None if e_prov_lossy is None else e_prov_lossy - 200.0,
        "edges": edges, "p10": float(np.percentile(edges, 10)),
    })


# ------------------------------------------------------------------ 3. limiter
BS1770 = np.array([
    [0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000, -0.0594482421875, 0.1373291015625,
     0.9721679687500, -0.1022949218750, 0.0476074218750, -0.0266113281250, 0.0148925781250, -0.0083007812500],
    [-0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250, -0.1665039062500, 0.4650878906250,
     0.7797851562500, -0.2003173828125, 0.1015625000000, -0.0582275390625, 0.0330810546875, -0.0189208984375],
    [-0.0189208984375, 0.0330810546875, -0.0582275390625, 0.1015625000000, -0.2003173828125, 0.7797851562500,
     0.4650878906250, -0.1665039062500, 0.0891113281250, -0.0517578125000, 0.0292968750000, -0.0291748046875],
    [-0.0083007812500, 0.0148925781250, -0.0266113281250, 0.0476074218750, -0.1022949218750, 0.9721679687500,
     0.1373291015625, -0.0594482421875, 0.0332031250000, -0.0196533203125, 0.0109863281250, 0.0017089843750]])


def limiter_gain(m, fs):
    """The streaming limiter; returns g[n], the gain for output sample n (which is m[n - la - 6])."""
    c = 10 ** (-1 / 20)
    la = int(np.rint(0.005 * fs))
    k1 = int(np.rint(0.001 * fs))
    k2 = la + 2 - k1
    holdn = int(np.rint(0.020 * fs))
    af = math.exp(-1 / (fs * 0.080))
    as_ = math.exp(-1 / (fs * 0.400))
    ab = math.exp(-1 / (fs * 0.5))
    n = len(m)
    hist = np.zeros(12)
    pk = {}
    need = {}
    r, hold, busy = 1.0, 0, 0.0
    b1 = [1.0] * k1
    b2 = [1.0] * k2
    s1, s2 = float(k1), float(k2)
    g = np.zeros(n)
    for t in range(n):
        hist = np.roll(hist, 1)
        hist[0] = m[t]
        v = max(abs(float(np.dot(BS1770[p], hist))) for p in range(4))
        pk[t - 6] = max(pk.get(t - 6, 0.0), v)
        pk[t - 5] = max(pk.get(t - 5, 0.0), v)
        j = t - 6
        need[j] = min(1.0, c / max(pk[j], 1e-12))
        w = min(need.get(i, 1.0) for i in range(j - la, j + 1))
        busy = ab * busy + (1 - ab) * (1.0 if w < 0.999 else 0.0)
        if w <= r:
            r = w
            hold = holdn
        elif hold > 0:
            hold -= 1
        else:
            a = af + (as_ - af) * min(1.0, 2 * busy)
            r += (1 - a) * (w - r)
        s1 += r - b1[t % k1]
        b1[t % k1] = r
        o1 = s1 / k1
        s2 += o1 - b2[t % k2]
        b2[t % k2] = o1
        o2 = s2 / k2
        g[t] = min(o2, need.get(j - la, 1.0))
    return g, la


def limiter():
    fs = 48000
    m = np.where(np.arange(2000) < 500, 0.5, 2.0)
    g, la = limiter_gain(m, fs)
    dump("frozen-limiter.json", {"fs": fs, "stepAt": 500, "before": 0.5, "after": 2.0, "lookahead": la,
                                 "from": 450, "gain": [float("%.10g" % v) for v in g[450:1450]]})


# ------------------------------------------------------------------ 4. CrystalX+ stage gains
def shelf(sr, f0, gain_db, kind, S):
    A = 10 ** (gain_db / 40); w = 2 * np.pi * f0 / sr; cw, sw = np.cos(w), np.sin(w)
    alpha = sw / 2 * np.sqrt((A + 1 / A) * (1 / S - 1) + 2); sA = 2 * np.sqrt(A) * alpha
    if kind == "low":
        b = [A * ((A + 1) - (A - 1) * cw + sA), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - sA)]
        a = [(A + 1) + (A - 1) * cw + sA, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - sA]
    else:
        b = [A * ((A + 1) + (A - 1) * cw + sA), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - sA)]
        a = [(A + 1) - (A - 1) * cw + sA, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - sA]
    return np.array(b) / a[0], np.array(a) / a[0]


def crystalx():
    fs = 48000
    freqs = [20.0, 50.0, 121.0, 300.0, 700.0, 1000.0, 2500.0, 5000.0, 9000.0, 16000.0]
    w = 2 * np.pi * np.array(freqs) / fs

    def ba(b, a):
        return freqz(b, a, worN=w)[1]

    def sos(s):
        return sosfreqz(s, worN=w)[1]

    db = lambda h: [float(20 * np.log10(abs(v))) for v in h]
    hs = ba(*shelf(fs, min(9000.0, 0.40 * fs), 1.5, "high", 0.7))
    ls = ba(*shelf(fs, 121.0, 5.5, "low", 0.3))
    g = 10 ** (-7.5 / 20)
    lp1 = sos(butter(1, 700, "low", fs=fs, output="sos"))
    hp1 = sos(butter(1, 700, "high", fs=fs, output="sos"))
    one = np.ones(len(freqs))
    stages = {
        "preGain": db(one * 10 ** (-3 / 20)),
        "highShelf": db(hs),
        "lowShelfHeadphones": db(1 + 1.0 * (ls - 1)),
        "lowShelfSpeaker": db(1 + 0.55 * (ls - 1)),
        "sidechainLp": db(sos(butter(2, 120, "low", fs=fs, output="sos"))),
        "exciterBandPass": db(sos(butter(2, [2500, 6000], "band", fs=fs, output="sos"))),
        "exciterHighPass": db(sos(butter(2, 5000, "high", fs=fs, output="sos"))),
        "crossfeedLp": db(lp1),
        "crossfeedHp": db(hp1),
        "crossfeedMono": db((1 + g * hp1 + g * lp1) / (1 + g)),
        "crossfeedHardLeftRatio": db(g * lp1 / (1 + g * hp1)),
        "widthHighPass": db(sos(butter(2, 300, "high", fs=fs, output="sos"))),
        "makeup": db(one * 10 ** (1.0 / 20)),
    }
    dump("frozen-crystalx.json", {"fs": fs, "freqs": freqs, "stagesDb": stages})


if __name__ == "__main__":
    bwe_frames()
    detector()
    limiter()
    crystalx()
