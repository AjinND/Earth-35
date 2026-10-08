export type Architecture={style:string;width:number;depth:number;height:number;angle:number;roof:"gable"|"hip"|"flat"|"shed";color:string;roofColor:string};
const facades=["#e5d4b5","#c7d4c4","#c4b39c","#dfc1a7","#bac9cd","#e4ddd0","#b9bda6"];
const roofs=["#a5654b","#61736a","#75858b","#8d795b"];
export function architecture(id:string,type:string):Architecture {
  let hash=2166136261;for(const c of id){hash^=c.charCodeAt(0);hash=Math.imul(hash,16777619);}hash>>>=0;
  const n=hash%5,house=type==="house";
  return {style:house?["villa","cottage","two-story","courtyard-house","bungalow"][n]:["small-apartment","terrace-apartment","large-apartment","stepped-apartment","narrow-apartment"][n],
    width:house?[6,4.3,4.8,5.8,5.5][n]:[5.4,6,6.2,5.8,4.2][n],
    depth:house?[5.1,5.5,4.4,5.2,4.5][n]:[4.4,4.9,5.5,5.2,5.5][n],
    height:house?[3.1,2.7,5.6,3.6,3][n]:[5.4,7.5,12.2,9.6,8.2][n],
    angle:((hash>>>8)%101/100-.5)*.16,roof:house?(["hip","gable","gable","flat","shed"] as const)[n]:n===0?"gable":"flat",
    color:facades[(hash>>>16)%facades.length],roofColor:roofs[(hash>>>20)%roofs.length]};
}
