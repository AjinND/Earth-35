import type {Building} from "@/lib/types";
import {riverBounds,riverOffset} from "@/lib/scenic-layout";
import {RIVER,svgLine} from "@/lib/organic-layout";
import {outdoorPatch} from "@/lib/outdoor-layout";
import {LAKE_SHORE,ALL_TRAILS,LANDSCAPES} from "@/lib/landscapes";
import {islandPath} from "@/lib/island";

export function ScenicMapTerrain({buildings,organic=false}:{buildings:readonly Building[];organic?:boolean}) {
  const river=riverBounds(buildings);
  return <g aria-hidden="true" pointerEvents="none">
    {organic&&<><defs><clipPath id="scenic-island-coast"><path d={islandPath}/></clipPath></defs><g clipPath="url(#scenic-island-coast)">{LANDSCAPES.coastalZones.map(zone=><ellipse key={zone.id} cx={zone.x} cy={zone.z} rx={zone.radiusX} ry={zone.radiusZ} fill={zone.kind==="cove"?"#dfd0aa":"#95a295"} opacity=".65"/>)}</g><path d={`${svgLine(LAKE_SHORE)} Z`} fill="#347e92"/>{ALL_TRAILS.map((trail,index)=><path key={index} d={svgLine(trail)} fill="none" stroke="#c4a77a" strokeWidth="1.7"/>)}</>}
    {buildings.filter(b=>(b.outdoorRadius??0)>0).map(b=><path key={b.id} d={outdoorPatch(b,buildings).cells.map(p=>`M${p.x-1},${p.z-1}h2v2h-2z`).join("")} fill={b.id.includes("beach")?"#d9c69e":b.type==="plaza"?"#cbbfa6":"#90ad79"} opacity=".85"/>)}
    {organic?<path d={svgLine(RIVER)} fill="none" stroke="#378ca4" strokeWidth="4.4"/>:river&&<><path d={Array.from({length:25},(_,i)=>{const z=river.from+(river.to-river.from)*i/24;return `${i?"L":"M"} ${river.bankX+riverOffset(z)} ${z}`;}).join(" ")} fill="none" stroke="#378ca4" strokeWidth="4.4"/>
      <path d={`M ${river.bankX} ${river.from} V ${river.to}`} stroke="#c4a77a" strokeWidth="1.8"/></>}
    {organic&&LANDSCAPES.bridges.map(bridge=><rect key={bridge.id} x={bridge.x-bridge.length/2} y={bridge.z-bridge.width/2} width={bridge.length} height={bridge.width} fill="#b99e72" stroke="#887958" strokeWidth=".4"/>)}
    {buildings.filter(b=>b.type==="mountain"||b.type==="hill_station").map(b=><path key={b.id} d={b.type==="mountain"?`M ${b.x-5} ${b.z-8} L ${b.x} ${b.z-17} L ${b.x+5} ${b.z-8} Z`:`M ${b.x-5} ${b.z-8} Q ${b.x} ${b.z-18} ${b.x+5} ${b.z-8} Z`} fill={b.type==="mountain"?"#7d8e84":"#76985e"}/>)}
  </g>;
}
export function ScenicMapSymbol({building:b}:{building:Building}) {
  if(b.type==="river"||b.type==="riverside_walk")return <circle cx={b.x} cy={b.z} r="2.3" fill="#dfcba7" stroke="#447861" strokeWidth=".7"/>;
  if(b.type==="trekking_trail")return <path d={`M ${b.x-2} ${b.z+3} Q ${b.x+3} ${b.z} ${b.x-1} ${b.z-3}`} fill="none" stroke="#976936" strokeWidth="2.2"/>;
  return <path d={`M ${b.x-3} ${b.z+2} L ${b.x} ${b.z-3} L ${b.x+3} ${b.z+2} Z`} fill={b.type==="mountain"?"#7d8e84":"#76985e"}/>;
}
