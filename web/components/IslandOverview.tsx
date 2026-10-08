"use client";
import {Building2,Globe2,Map,Users,Waves} from "lucide-react";
import type {CameraCommand,CameraView} from "./City";
import {ISLAND_AREA_KM2} from "@/lib/island";
import {OCEAN_COVERAGE_PERCENT} from "@/lib/planet";

export default function IslandOverview({view,onView,showInfo}:{view:CameraView|null;onView:(type:CameraCommand["type"])=>void;showInfo:boolean}) {
  return <>
    <div className="island-view-switch" role="group" aria-label="Map scale presets">
      {([{type:"planet",label:"Planet",icon:Globe2},{type:"island",label:"Island",icon:Map},{type:"settlement",label:"Settlement",icon:Building2},{type:"residents",label:"Residents",icon:Users}] as const).map(({type,label,icon:Icon})=><button key={type} aria-label={`View ${label.toLowerCase()}`} aria-pressed={view?.scale===type} disabled={!view} onClick={()=>onView(type)}><Icon size={14}/><span>{label}</span></button>)}
    </div>
    {view?.scale==="planet"&&<aside className="planet-overview" aria-label="Planet geography">
      <span className="eyebrow"><Globe2 size={14}/> EARTH 35 / WORLD ATLAS</span>
      <h2>An ocean of<br/>possibility.</h2>
      <p>A shared world, with one<br/>inhabited island.</p>
      <div className="planet-facts"><strong>{Math.round(OCEAN_COVERAGE_PERCENT)}<span>% ocean</span></strong><small>OTHER LANDMASSES · UNDEVELOPED</small></div>
      <button onClick={()=>onView("island")}>Return to New Eden <Map size={13}/></button>
    </aside>}
    {showInfo&&<aside className="island-overview" aria-label="Island geography">
      <span className="eyebrow"><Waves size={14}/> THE FIRST ISLAND</span>
      <h2>A little land.<br/>A world ahead.</h2>
      <p>New Eden, surrounded by<br/>wild shores and open ocean.</p>
      <div className="island-area"><strong>{ISLAND_AREA_KM2}<span> km²</span></strong><small>ONE INHABITED ISLAND</small></div>
      <span className="island-access"><i/> Settlement and wilderness · Open to explore</span>
    </aside>}
    {view&&<div className="island-scale" aria-label="Map distance scale" title={view.scale==="planet"?"Approximate equatorial distance":"Local map distance"}><span>{view.scaleMeters>=1000?`${view.scaleMeters/1000} km`:`${view.scaleMeters} m`}</span><i style={{width:view.scalePixels}}/></div>}
  </>;
}
