import {architecture,architectureHash,BUILDING_DOOR_Z,BUILDING_WALL_HALF,type Architecture} from "./architecture";
import type {Building} from "./types";

export type BuildingBox={x:number;y:number;z:number;width:number;height:number;depth:number;color:string;role:string};
export type BuildingRoof=BuildingBox&{shape:Architecture["roof"]};
export type BuildingDesign={architecture:Architecture;solid:BuildingBox[];glass:BuildingBox[];roofs:BuildingRoof[];top:number;door:{x:number;z:number}};
type Floor={x:number;z:number;width:number;depth:number;bottom:number;height:number};
const stone="#a6a594",metal="#4a625e",timber="#88684e",glass="#405d60";

/** Pure geometry specification shared by rendering, bounds and entrance tests. */
export function buildingDesign(building:Building,detailed=true):BuildingDesign {
  const a=architecture(building.id,building.type,building.district),house=building.type==="house",office=building.type==="office";
  const solid:BuildingBox[]=[],windows:BuildingBox[]=[],roofs:BuildingRoof[]=[];
  const door={x:(building.entrance?.x??building.x)-building.x,z:(building.entrance?.z??building.z+BUILDING_DOOR_Z)-building.z};
  const box=(role:string,x:number,y:number,z:number,width:number,height:number,depth:number,color:string)=>solid.push({role,x,y,z,width,height,depth,color});
  const base=.16;
  box("foundation",0,.04,0,6.9,.24,6.9,stone);
  const floors:Floor[]=Array.from({length:a.floors},(_,index)=>{
    const setback=index>0&&(!house&&(a.variant===1||a.variant===3)||office&&a.variant===2);
    const narrow=index>0&&(!house&&a.variant===4||office&&a.variant===0);
    return {x:narrow?-.65:0,z:setback?-.4:0,width:narrow?5.4:a.width,depth:setback?5.9:a.depth,bottom:base+index*a.floorHeight,height:a.floorHeight};
  });
  for(const [index,floor] of floors.entries()) {
    const {x,z,width,depth,bottom,height}=floor;
    box("wall",x,bottom+height/2,z,width,height,depth,index===0&&office?stone:a.color);
    box("floor-band",x,bottom+height-.07,z,width+.12,.14,depth+.12,a.trim);
    const windowHeight=office?1.6:1.25,windowWidth=office?1.45:1.05,y=bottom+(index===0?1.65:1.5);
    for(const face of ["front","back","left","right"] as const)for(const slot of [-1,0,1]) {
      if(face==="front"&&index===0&&slot===0)continue;
      const front=face==="front"||face==="back",sign=face==="front"||face==="right"?1:-1;
      const wx=x+(front?slot*width*.29:sign*(width/2+.022));
      const wz=z+(front?sign*(depth/2+.022):slot*depth*.29);
      const lit=(architectureHash(`${building.id}:${index}:${face}:${slot}`)%4)!==0;
      const pane={role:"window",x:wx,y,z:wz,width:front?windowWidth:.055,height:windowHeight,depth:front?.055:windowWidth,color:glass};
      (lit?windows:solid).push(pane);
      if(!detailed)continue;
      for(const side of [-1,1])box("window-frame",wx+side*(front?1:0)*(windowWidth/2+.045),y,wz+side*(front?0:1)*(windowWidth/2+.045),front?.09:.10,windowHeight+.18,front?.10:.09,a.trim);
      for(const side of [-1,1])box("window-frame",wx,y+side*(windowHeight/2+.045),wz,front?windowWidth+.18:.10,.09,front?.10:windowWidth+.18,a.trim);
      box("window-mullion",wx,y,wz,front?.06:.10,windowHeight,front?.10:.06,office?metal:a.trim);
      if(house&&a.variant!==4&&face==="front")for(const side of [-1,1])box("shutter",wx+side*(windowWidth/2+.22),y,wz,.26,windowHeight,.09,a.accent);
    }
    if(detailed&&!house&&index>0&&!office&&a.variant!==4) {
      const front=z+depth/2;
      for(const side of [-1,1]) {
        const bx=x+side*width*.29;
        box("balcony-deck",bx,bottom+.06,front+.10,1.8,.14,.42,a.trim);
        box("balcony-rail",bx,bottom+.87,front+.29,1.8,.07,.06,metal);
        for(const dx of [-.84,-.42,0,.42,.84])box("balcony-post",bx+dx,bottom+.48,front+.29,.045,.78,.045,metal);
      }
    }
    if(office)for(const side of [-1,1])box("office-pier",x+side*(width/2-.20),bottom+height/2,z+depth/2+.06,.24,height,.12,a.accent);
  }
  const topFloor=floors.at(-1)!,roofBase=base+a.height;
  const roofHeight=a.roof==="flat"?.22:a.roof==="shed"?.85:1.35;
  roofs.push({role:"roof",shape:a.roof,x:topFloor.x,y:roofBase,z:topFloor.z,width:topFloor.width+.24,height:roofHeight,depth:topFloor.depth+.24,color:a.roof==="flat"?"#b2b5a6":a.roofColor});
  let top=roofBase+roofHeight;
  if(a.roof==="flat") {
    for(const side of [-1,1]) {
      box("parapet",topFloor.x+side*(topFloor.width/2-.08),roofBase+.4,topFloor.z,.16,.6,topFloor.depth,a.trim);
      box("parapet",topFloor.x,roofBase+.4,topFloor.z+side*(topFloor.depth/2-.08),topFloor.width,.6,.16,a.trim);
    }
    top=roofBase+.7;
    {
      box("roof-core",topFloor.x-.8,roofBase+.68,topFloor.z-1,1.5,.9,1.7,a.accent);
      box("roof-core-cap",topFloor.x-.8,roofBase+1.17,topFloor.z-1,1.64,.12,1.84,a.trim);
      top=roofBase+1.23;
      for(const side of [-1,1]) {
        const x=topFloor.x+side*(topFloor.width/2-.85),z=topFloor.z+topFloor.depth/2-.9;
        box("roof-planter",x,roofBase+.38,z,1.05,.3,.65,timber);
        box("roof-planting",x,roofBase+.6,z,.9,.25,.5,"#738663");
      }
    }
  }
  {
    box("base-course",0,.35,0,6.76,.34,6.76,stone);
    // The approach ends at z=3.6, just in front of the door on the wall.
    const front=BUILDING_WALL_HALF;
    box("door-surround",door.x,1.32,front+.065,1.58,2.36,.16,a.trim);
    box("door",door.x,1.26,front+.16,1.22,2.2,.065,house?timber:metal);
    box("door-glazing",door.x,1.61,front+.205,house?.6:.98,house?.7:1.5,.03,glass);
    if(office)box("door-mullion",door.x,1.26,front+.215,.06,2.1,.04,a.trim);
    box("door-handle",door.x+.39,1.15,front+.25,.05,.28,.055,"#c7b381");
    box("threshold",door.x,.12,(front+door.z)/2,1.8,.16,Math.max(.3,door.z-front+.25),stone);
    if(!house||a.variant!==0&&a.variant!==4)box("entry-canopy",door.x,2.65,front+.09,office?2.8:2.1,.16,.64,a.accent);
    box("entry-lamp-case",door.x+1.02,2.04,front+.10,.25,.39,.18,metal);
    windows.push({role:"entry-light",x:door.x+1.02,y:2.04,z:front+.20,width:.16,height:.26,depth:.035,color:"#ffe3b2"});
    box("address-plaque",door.x-.99,1.75,front+.10,.32,.18,.09,a.accent);
    if(office)box("office-sign",0,3.02,front+.06,2.8,.38,.10,a.accent);
    if(house&&(a.variant===0||a.variant===4)) {
      box("veranda-eave",0,2.65,front-.15,6.55,.16,.94,a.accent);
      for(const side of [-1,1])box("veranda-column",side*2.97,1.38,front+.08,.12,2.5,.12,a.trim);
    }
    if(house&&a.roof!=="flat") {
      box("chimney",-1.65,roofBase+.65,-1.4,.6,1.55,.66,stone);
      box("chimney-cap",-1.65,roofBase+1.45,-1.4,.76,.14,.82,a.trim);
      top=Math.max(top,roofBase+1.52);
    }
    if(floors.length>1&&floors[1].depth<a.depth) {
      box("terrace-rail",0,base+a.floorHeight+.85,3.24,6.42,.08,.08,metal);
      for(const x of [-3.16,-1.6,0,1.6,3.16])box("terrace-post",x,base+a.floorHeight+.44,3.24,.06,.8,.06,metal);
    }
  }
  return {architecture:a,solid,glass:windows,roofs,top,door};
}
