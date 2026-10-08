import type {Building,ConstructionSiteView} from "@/lib/types";
import {time} from "@/lib/api";
import {constructionProgress,constructionStage} from "@/lib/construction";

export default function PlaceHours({building,site}:{building:Building;site?:ConstructionSiteView}) {
  if(building.type==="construction") {
    const progress=constructionProgress(site),workers=site?.workers??[];
    return <div className="venue-details"><p className="muted venue-hours">Under construction · Closed to visitors</p><p className="muted">Future {building.plannedType??"building"}</p>{site&&<div className="construction-details" aria-label="Construction progress">
      <strong>{progress.percent}% complete · {constructionStage(progress.fraction)}</strong><progress max={100} value={progress.percent} aria-label={`${building.name} construction progress`}/>
      <p>{(progress.completed/60).toLocaleString("en-US",{maximumFractionDigits:1})} / {(progress.required/60).toLocaleString("en-US",{maximumFractionDigits:1})} labor hours completed</p>
      <p>{workers.length?`Building now: ${workers.map(worker=>worker.name).join(", ")}`:site.laborMode==="self"?"Awaiting work by the builder":"No residents currently building"}</p>
      <p className="muted">{site.laborMode==="self"?"Self build":site.acceptingWorkers?"Hiring resident workers":"Paid resident labor"} · {time(site.workStartMinute)}–{time(site.workEndMinute)}</p>
    </div>}</div>;
  }
  const hours=building.hours;
  return <div className="venue-details">
    {hours&&<p className="muted venue-hours">{hours.openNow?"Open now":"Closed now"} · {hours.openMinute===hours.closeMinute?"All day":`${time(hours.openMinute)}–${time(hours.closeMinute)}`}</p>}
    {building.entrance?.side==="front"&&<p className="muted">Entrance on the front side</p>}
  </div>;
}
