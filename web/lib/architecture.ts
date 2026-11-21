export type Architecture = {
  style:string; width:number; depth:number; height:number; angle:number;
  roof:"gable"|"hip"|"flat"|"shed"; color:string; roofColor:string;
  accent:string; trim:string; floors:number; floorHeight:number; variant:number;
};

// The server reserves a 7 × 7 navigation envelope around every enclosed place.
// Keep ground-floor walls at ±3.35 and the existing front entrance at z=3.6.
// Variation belongs above that footprint, never in a rotated collision boundary.
export const BUILDING_WALL_HALF = 3.35;
export const BUILDING_ROUTE_HALF = 3.5;
export const BUILDING_VIEWER_HALF = 3.9;
export const BUILDING_DOOR_Z = 3.6;

export function architectureHash(value:string) {
  let hash=2166136261;
  for(const character of value){hash^=character.charCodeAt(0);hash=Math.imul(hash,16777619);}
  return hash>>>0;
}
const palettes = [
  {color:"#e5d9c3",accent:"#728578",roofColor:"#69766f"},
  {color:"#d8c5ac",accent:"#875e49",roofColor:"#ac7257"},
  {color:"#cad5cd",accent:"#546c69",roofColor:"#52666b"},
  {color:"#deddd0",accent:"#867a63",roofColor:"#7b8176"},
];
export const redesignedBuilding = (type:string) => ["house","apartment","office"].includes(type);

export function architecture(id:string,type:string,district=""):Architecture {
  const hash=architectureHash(id),house=type==="house",office=type==="office";
  const variant=hash%(office?3:5);
  const floors=house?[1,1,2,1,1][variant]:office?[3,4,2][variant]:[2,3,4,3,3][variant];
  const height=house?[3.1,2.8,5.8,3.3,3][variant]:office?[9.3,12.4,6.8][variant]:[5.6,8.4,11.2,9,8.1][variant];
  // Neighboring addresses share a district palette, with limited trim variation.
  const palette=palettes[architectureHash(district||type)%palettes.length];
  return {
    style:house?["villa","cottage","two-story","courtyard-house","bungalow"][variant]:office?["courtyard-office","civic-office","terraced-office"][variant]:["small-apartment","terrace-apartment","large-apartment","stepped-apartment","narrow-apartment"][variant],
    width:BUILDING_WALL_HALF*2,depth:BUILDING_WALL_HALF*2,height,angle:0,
    roof:house?(["hip","gable","gable","flat","shed"] as const)[variant]:!office&&variant===0?"gable":"flat",
    ...palette,trim:(hash>>>16)%2?"#f0e7d5":"#e1d9c7",floors,floorHeight:height/floors,variant,
  };
}
