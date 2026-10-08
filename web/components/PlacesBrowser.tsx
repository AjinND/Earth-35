"use client";
import {useState} from "react";
import {ChevronRight} from "lucide-react";
import type {World} from "@/lib/types";
import {time} from "@/lib/api";

export default function PlacesBrowser({world,onSelect}:{world:World;onSelect:(id:string)=>void}) {
  const [all,setAll]=useState(false);
  const visitors=(id:string)=>world.agents.filter(a=>a.location===id).length;
  const places=world.buildings.filter(b=>all||b.social).slice().sort((a,b)=>Number(b.social)-Number(a.social)||visitors(b.id)-visitors(a.id)||a.name.localeCompare(b.name));
  return <div className="places-popover">
    <h3>Places in New Eden</h3>
    <div className="place-filters" role="group" aria-label="Place categories">
      <button aria-pressed={!all} onClick={()=>setAll(false)}>Places to meet</button>
      <button aria-pressed={all} onClick={()=>setAll(true)}>All places</button>
    </div>
    <p className="place-hint">{all?"Explore the city’s homes, businesses, and gathering places.":"Choose a spot to relax, take a riverside walk, go trekking, browse a mall, or meet someone new."}</p>
    {places.map(b=><button className="place-row" key={b.id} aria-label={b.name} onClick={()=>onSelect(b.id)}>
      <span>{b.name}<small>{b.type.replaceAll("_"," ")} · {b.district}</small>{b.type==="construction"?<small>Under construction · Closed to visitors</small>:b.hours&&<small>{b.hours.openNow?"Open":"Closed"} · {b.hours.openMinute===b.hours.closeMinute?"All day":`${time(b.hours.openMinute)}–${time(b.hours.closeMinute)}`}</small>}</span>
      <span className="place-visitors">{visitors(b.id)} here<ChevronRight size={12}/></span>
    </button>)}
  </div>;
}
