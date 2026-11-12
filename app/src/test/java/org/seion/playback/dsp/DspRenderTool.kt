package org.seion.playback.dsp

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Meters
import org.seion.playback.dsp.testutil.Signals
import org.seion.playback.dsp.testutil.WavIo

/**
 * An offline A/B renderer, skipped unless `-Pseion.render.in=<dir>`. It runs the production engine (CrystalX,
 * RenovaPure with its own detector, the limiter) on every `<name>.wav` of the input folder;
 * a `<name>.ref.wav` next to it is taken as the lossless original (LSD and HF error). The codec class comes from
 * the name (mp3/aac/m4a/opus/vorbis/ogg → lossy, else lossless). Outputs go to `-Pseion.render.out` (default
 * `build/dsp-render` under the app module, which git ignores): off, CrystalX+ headphones and speaker, RenovaPure,
 * both — each also loudness-matched to "off" within ±0.2 LU — metrics.json and index.html, a blind A/B page.
 */
class DspRenderTool {
    private class Render(val id: String, val label: String, val cx: Boolean, val rp: Boolean, val voicing: Voicing)

    private val renders =
        listOf(
            Render("off", "Off", false, false, Voicing.HEADPHONES),
            Render("cx-headphones", "CrystalX+ (headphones)", true, false, Voicing.HEADPHONES),
            Render("cx-speaker", "CrystalX+ (speaker)", true, false, Voicing.SPEAKER),
            Render("renovapure", "RenovaPure", false, true, Voicing.HEADPHONES),
            Render("both", "Both (headphones)", true, true, Voicing.HEADPHONES)
        )

    /** Captures the limiter's gain to report gain-reduction percentiles. */
    private class Recording(private val inner: GainComputer) : GainComputer {
        val gains = ArrayList<Float>()

        override fun configure(fs: Int, ch: Int) = inner.configure(fs, ch)

        override fun reset() = inner.reset()

        override fun gains(m: FloatArray, frames: Int, g: FloatArray) {
            inner.gains(m, frames, g)
            for (i in 0 until frames) gains += g[i]
        }
    }

    @Test fun render() {
        val inDir = System.getProperty("seion.render.in")
        assumeTrue(inDir != null)
        val out = File(System.getProperty("seion.render.out") ?: "build/dsp-render").apply {
            mkdirs()
        }
        val inputs = File(inDir!!).listFiles { f -> f.name.endsWith(".wav") && !f.name.endsWith(".ref.wav") }!!.sortedBy { it.name }
        val metrics = StringBuilder("{\n")
        val clips = ArrayList<Pair<String, List<String>>>()
        for ((ci, file) in inputs.withIndex()) {
            val name = file.name.removeSuffix(".wav")
            val wav = WavIo.read(file)
            val stereo = if (wav.channels == 2) wav.data else Signals.stereo(wav.data)
            val lossy = Regex("mp3|aac|m4a|opus|vorbis|ogg").containsMatchIn(name.lowercase())
            val ref = File(file.parentFile, "$name.ref.wav").takeIf { it.isFile }?.let { WavIo.read(it) }
            val offLufs = Meters.lufs(stereo, 2, wav.fs)
            metrics.append("  \"$name\": {\"fs\": ${wav.fs}, \"codec\": \"${if (lossy) "lossy" else "lossless"}\", \"renders\": {\n")
            val files = ArrayList<String>()
            for ((ri, r) in renders.withIndex()) {
                val lim = Recording(TruePeakLimiter())
                val rp = RenovaPure()
                val e = DspEngine(cx = CrystalX(), rp = rp, limiter = lim).apply { configure(wav.fs, 2) }
                e.newStream(if (lossy) CodecClass.LOSSY else CodecClass.LOSSLESS, name)
                e.setParams(DspParams(crystalX = r.cx, renovaPure = r.rp, voicing = r.voicing))
                val l = e.latency
                val y = FloatArray(stereo.size + l * 2)
                System.arraycopy(stereo, 0, y, 0, stereo.size)
                var f = 0
                val buf = FloatArray(4096)
                while (f < y.size / 2) {
                    val n = minOf(2048, y.size / 2 - f)
                    System.arraycopy(y, f * 2, buf, 0, n * 2)
                    e.process(buf, n)
                    System.arraycopy(buf, 0, y, f * 2, n * 2)
                    f += n
                }
                val aligned = y.copyOfRange(l * 2, y.size)
                val lufs = Meters.lufs(aligned, 2, wav.fs)
                val tp = Meters.truePeakDb(aligned, 2, wav.fs)
                val match = Math.pow(10.0, (offLufs - lufs) / 20).toFloat()
                val matched = FloatArray(aligned.size) { aligned[it] * match }
                WavIo.write16(File(out, "$name.${r.id}.wav"), wav.fs, 2, aligned)
                WavIo.write16(File(out, "$name.${r.id}.matched.wav"), wav.fs, 2, matched)
                files += "$name.${r.id}.matched.wav"
                val gr = lim.gains.filter { it < 1f }.map { -20 * kotlin.math.log10(it.toDouble()) }.sorted()
                fun pct(q: Double) = if (gr.isEmpty()) 0.0 else gr[((gr.size - 1) * q).toInt()]
                val share = gr.size.toDouble() / lim.gains.size.coerceAtLeast(1)
                var lsd = "null"
                var hfErr = "null"
                if (ref != null && rp.cutoff > 0) {
                    val refStereo = if (ref.channels == 2) ref.data else Signals.stereo(ref.data)
                    val n = minOf(refStereo.size, aligned.size) / 2
                    val mid = FloatArray(n) { (aligned[it * 2] + aligned[it * 2 + 1]) / 2 }
                    val refMid = FloatArray(n) { (refStereo[it * 2] + refStereo[it * 2 + 1]) / 2 }
                    lsd = "%.3f".format(java.util.Locale.ROOT, Meters.lsd(refMid, mid, wav.fs, 16_000.0, 20_000.0))
                    val fc = rp.cutoff
                    hfErr =
                        "%.2f".format(
                            java.util.Locale.ROOT,
                            10 *
                                kotlin.math.log10(
                                    Meters.bandEnergy(mid, wav.fs, fc, 20_000.0) / Meters.bandEnergy(refMid, wav.fs, fc, 20_000.0)
                                )
                        )
                }
                metrics.append(
                    (
                        "    \"${r.id}\": {\"lufs\": %.2f, \"truePeakDbtp\": %.2f, \"matchGainDb\": %.2f, \"limiterGrP50Db\": %.2f, " +
                            "\"limiterGrP99Db\": %.2f, \"limitingShare\": %.3f, \"renovaPureCutoffHz\": %.0f, \"lsd16to20k\": %s, " +
                            "\"hfErrorDb\": %s}%s\n"
                        ).format(
                        java.util.Locale.ROOT, lufs, tp,
                        20 * kotlin.math.log10(
                            match.toDouble()
                        ),
                        pct(0.5), pct(0.99), share, rp.cutoff, lsd, hfErr,
                        if (ri < renders.size - 1) "," else ""
                    )
                )
            }
            metrics.append("  }}${if (ci < inputs.size - 1) "," else ""}\n")
            clips += name to files
        }
        metrics.append("}\n")
        File(out, "metrics.json").writeText(metrics.toString())
        File(out, "index.html").writeText(page(clips))
        println("render tool: ${inputs.size} inputs -> $out")
    }

    /** A static blind A/B page: per clip, pick two renders, randomise X/Y, reveal after the vote, keep a tally. */
    private fun page(clips: List<Pair<String, List<String>>>): String {
        val data = clips.joinToString(",\n") { (name, files) ->
            "{\"name\":\"$name\",\"files\":[${files.joinToString(",") { "\"$it\"" }}]}"
        }
        val labels = renders.joinToString(",") { "\"${it.id}\":\"${it.label}\"" }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Seion DSP blind A/B</title>
<style>
:root{--bg:#0d0d0f;--fg:#eceae4;--mute:#9a9890;--card:#1a1b1f;--acc:#e6d6a6}
@media (prefers-color-scheme: light){:root{--bg:#f6f5f1;--fg:#1a1a1a;--mute:#5b5a55;--card:#fff;--acc:#7a5b00}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,sans-serif;max-width:760px}
.card{background:var(--card);border-radius:12px;padding:14px;margin:12px 0}
select,button{font:inherit;padding:6px 10px;border-radius:8px}
button{background:var(--acc);color:#111;border:0;margin:4px}
.mute{color:var(--mute)} audio{width:100%;margin:4px 0}
</style></head><body>
<h1>Seion DSP — blind A/B</h1>
<p class="mute">Every render is loudness-matched to "Off" (±0.2 LU) and made with the production engine. Pick two
versions, listen to X and Y (their order is random), vote, then reveal. The tally stays in this browser.</p>
<div id="app"></div>
<script>
const CLIPS=[$data];
const LABELS={$labels};
let tally={};try{tally=JSON.parse(localStorage.getItem('seion-ab')||'{}')}catch(e){}
function save(){try{localStorage.setItem('seion-ab',JSON.stringify(tally))}catch(e){}}
function idOf(f){return f.split('.').slice(-3,-2)[0]}
const app=document.getElementById('app');
CLIPS.forEach((c,ci)=>{
  const d=document.createElement('div');d.className='card';
  const opts=c.files.map(f=>'<option value="'+f+'">'+LABELS[idOf(f)]+'</option>').join('');
  d.innerHTML='<h2>'+c.name+'</h2>A <select id="a'+ci+'">'+opts+'</select> vs B <select id="b'+ci+'">'+opts+'</select> '+
    '<button id="go'+ci+'">New trial</button><div id="t'+ci+'"></div>';
  app.appendChild(d);
  d.querySelector('#b'+ci).selectedIndex=Math.min(1,c.files.length-1);
  d.querySelector('#go'+ci).onclick=()=>{
    const a=d.querySelector('#a'+ci).value,b=d.querySelector('#b'+ci).value;
    const swap=Math.random()<0.5;const x=swap?b:a,y=swap?a:b;
    const t=d.querySelector('#t'+ci);
    t.innerHTML='<p>X</p><audio controls src="'+x+'"></audio><p>Y</p><audio controls src="'+y+'"></audio>'+
      '<button data-v="x">Prefer X</button><button data-v="y">Prefer Y</button><p class="mute" id="r'+ci+'"></p>';
    t.querySelectorAll('button').forEach(btn=>btn.onclick=()=>{
      const win=btn.dataset.v==='x'?x:y,lose=win===x?y:x;
      const k=c.name+': '+LABELS[idOf(win)]+' over '+LABELS[idOf(lose)];
      tally[k]=(tally[k]||0)+1;save();
      t.querySelector('#r'+ci).textContent='X was '+LABELS[idOf(x)]+', Y was '+LABELS[idOf(y)]+'. Tally: '+
        Object.entries(tally).filter(e=>e[0].startsWith(c.name)).map(e=>e[0].slice(c.name.length+2)+' '+e[1]).join('; ');
    });
  };
});
</script></body></html>
"""
    }
}
