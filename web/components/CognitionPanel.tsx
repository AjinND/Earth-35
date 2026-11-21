import {time} from "@/lib/api";
import type {ResidentMind} from "@/lib/types";

function when(minute:number) {return `Day ${Math.floor(minute/1440)+1}, ${time(minute)}`;}

export default function CognitionPanel({mind,minute}:{mind?:ResidentMind|null;minute:number}) {
  const intention=mind?.intention,scene=mind?.scene;
  const visitedCount=Object.keys(mind?.visits??{}).length;
  const notices=[...(mind?.notices??[])].sort((a,b)=>b.minute-a.minute||b.revision-a.revision).slice(0,5);
  return <section className="surface" aria-label="Awareness and intentions">
    <div className="section-heading"><h2>Awareness &amp; intentions</h2><span className="private-tag">PRIVATE</span></div>
    <p className="muted">What this resident has noticed, chosen and learned from recent experience.</p>
    {!mind||mind.observedMinute<0?<p className="muted">Waiting for the resident to observe their surroundings.</p>:<>
      <h3>Surroundings</h3>
      {scene&&<><p>{scene.place} · {scene.daylight} · {scene.weather} · {scene.temperature}°C</p>
        {scene.purpose&&<p>{scene.purpose}</p>}
        {!!scene.objects.length&&<p>Within reach: {scene.objects.join(", ")}</p>}
        {!!scene.visiblePlaces.length&&<p>In view: {scene.visiblePlaces.join(", ")}</p>}</>}
      <small className="muted">Last observed {when(mind.observedMinute)}. Surroundings may have changed since then.</small>
    </>}
    <h3>Current intention</h3>
    {intention?<><p><strong>{intention.focus}</strong></p><p>Next step: {intention.nextStep}</p><p>{intention.reason}</p>
      <p className="muted">{minute>=intention.reviewMinute?"Due for review":"Review at"} · {when(intention.reviewMinute)} · A plan, awaiting outcomes.</p></>:
      <p className="muted">No intention recorded yet. The resident chooses its own next aim.</p>}
    {mind?.feeling&&<><h3>Self-assessment</h3><p>{mind.feeling}</p><small className="muted">Agent-authored · {when(mind.feelingMinute)}</small></>}
    {!!notices.length&&<details><summary>Recent observations</summary><div className="action-list">
      {notices.map(n=><article key={n.revision}><p>{n.description}</p><small>{when(n.minute)}</small></article>)}
    </div></details>}
    {!!mind?.outcomes?.length&&<details><summary>Recent outcomes</summary><div className="action-list">
      {[...mind.outcomes].reverse().slice(0,5).map(o=><article key={o.id}><span className={`pill ${o.status==="rejected"?"warning":""}`}>{o.status==="accepted"?"In progress":o.status}</span>
        <p>{o.action.replaceAll("_"," ")} · {o.result}</p><small>{when(o.minute)}</small></article>)}
    </div></details>}
    {!!mind?.reflections?.length&&<details><summary>What changed their thinking</summary><div className="memory-list">
      {[...mind.reflections].reverse().map(r=><article key={r.outcomeId}><p>{r.lesson}</p><small>Personal interpretation · {when(r.minute)}</small></article>)}
    </div></details>}
    {visitedCount>0&&<p className="muted">{visitedCount} {visitedCount===1?"place":"places"} remembered from actual visits.</p>}
  </section>;
}
