import {COASTLINE,ISLAND_CENTER,METERS_PER_MAP_UNIT,type IslandPoint} from "./island";

// A reference sphere gives this fixed atlas dimensions; it is not simulated terrain.
export const PLANET_RADIUS_KM = 6000;
export const MAP_UNITS_PER_KM = 1000/METERS_PER_MAP_UNIT;
export const PLANET_CIRCUMFERENCE_KM = 2*Math.PI*PLANET_RADIUS_KM;
export const PLANET_SURFACE_AREA_KM2 = 4*Math.PI*PLANET_RADIUS_KM**2;
export const ISLAND_ANCHOR = {longitude:22,latitude:12} as const;
export const ATLAS_WIDTH = PLANET_CIRCUMFERENCE_KM*MAP_UNITS_PER_KM;
export const ATLAS_HEIGHT = Math.PI*PLANET_RADIUS_KM*MAP_UNITS_PER_KM;
const radians = Math.PI/180;
const unitsPerRadian = PLANET_RADIUS_KM*MAP_UNITS_PER_KM;

// North is -z, east is +x. Local settlement and coastline coordinates never move.
// Equirectangular distances distort away from the equator: this is a visual atlas,
// not a promise of geodesic routes or access to the undeveloped regions.
export function atlasPoint(longitude:number,latitude:number):IslandPoint {
  return {
    x:ISLAND_CENTER.x+(longitude-ISLAND_ANCHOR.longitude)*radians*unitsPerRadian,
    z:ISLAND_CENTER.z-(latitude-ISLAND_ANCHOR.latitude)*radians*unitsPerRadian,
  };
}
export const ATLAS_CENTER = atlasPoint(0,0);
export const ATLAS_BOUNDS = {
  minX:atlasPoint(-180,0).x,maxX:atlasPoint(180,0).x,
  minZ:atlasPoint(0,90).z,maxZ:atlasPoint(0,-90).z,
} as const;

type GeographicPoint = readonly [longitude:number,latitude:number];
export type Landmass = {
  id:string;
  name:string;
  color:string;
  outline:readonly IslandPoint[];
  label:IslandPoint;
  areaKm2:number;
};

// Integrate cos(latitude) over each straight atlas edge. A flat shoelace area
// would overcount high-latitude land and cannot establish ocean coverage.
export function sphericalAtlasArea(points:readonly GeographicPoint[]):number {
  return Math.abs(points.reduce((sum,[longitude,latitude],index)=>{
    const [nextLongitude,nextLatitude]=points[(index+1)%points.length];
    const a=latitude*radians,b=nextLatitude*radians;
    const meanSine=Math.abs(b-a)<1e-12?Math.sin(a):(Math.cos(a)-Math.cos(b))/(b-a);
    return sum+(nextLongitude-longitude)*radians*meanSine;
  },0))*PLANET_RADIUS_KM**2;
}

function landmass(id:string,name:string,color:string,label:GeographicPoint,points:readonly GeographicPoint[]):Landmass {
  return {id,name,color,outline:points.map(([longitude,latitude])=>atlasPoint(longitude,latitude)),
    label:atlasPoint(...label),areaKm2:sphericalAtlasArea(points)};
}

// Deterministic fictional silhouettes, separated from New Eden and the atlas seam.
// These coarse polygons carry no buildings, paths, actions, or server-owned state.
export const LANDMASSES:readonly Landmass[] = [
  landmass("vesper","Vesper Reach","#92aa89",[-115,28],[
    [-164,34],[-158,49],[-141,58],[-125,54],[-110,64],[-92,59],[-87,47],[-72,44],
    [-62,29],[-74,20],[-71,9],[-85,3],[-88,-11],[-106,-16],[-117,-5],[-127,7],
    [-147,11],[-153,25],
  ]),
  landmass("orison","Orison","#a2af92",[111,45],[
    [67,47],[74,60],[91,65],[103,75],[122,70],[136,61],[153,56],[159,40],
    [147,28],[129,25],[118,14],[104,19],[93,30],[76,31],
  ]),
  landmass("southmere","Southmere","#8ca58d",[107,-34],[
    [61,-22],[72,-9],[88,-13],[98,-3],[116,-10],[126,-21],[146,-27],[154,-41],
    [143,-50],[126,-48],[117,-65],[98,-61],[88,-48],[72,-45],[76,-33],
  ]),
  landmass("avel","Avel Coast","#a6b39a",[-54,-51],[
    [-99,-48],[-87,-35],[-70,-39],[-57,-31],[-42,-35],[-30,-47],[-17,-50],
    [-25,-62],[-44,-68],[-65,-64],[-76,-55],[-90,-59],
  ]),
  landmass("nacre-north","Nacre Isles","#b0b79a",[8,54],[
    [-8,57],[0,64],[12,60],[17,49],[9,43],[-1,46],
  ]),
  landmass("nacre-south","","#a1b394",[27,44],[
    [21,45],[27,49],[34,45],[31,36],[25,35],
  ]),
  landmass("pelagic-west","Pelagic Isles","#a6b6a0",[-138,-42],[
    [-150,-38],[-145,-31],[-135,-34],[-132,-43],[-141,-48],[-148,-44],
  ]),
  landmass("pelagic-south","","#a6b6a0",[-120,-62],[
    [-127,-59],[-118,-55],[-112,-62],[-118,-69],[-125,-66],
  ]),
  landmass("tamar","Tamar Isles","#a9b698",[18,-36],[
    [7,-34],[14,-26],[25,-29],[29,-39],[18,-45],[11,-42],
  ]),
  landmass("eastern-shoals","Eastern Shoals","#aab69b",[167,-10],[
    [162,-6],[170,-3],[175,-11],[170,-19],[163,-16],
  ]),
];

// Keep the island's existing 100 km² planar outline. Its spherical atlas footprint
// is slightly smaller at 12°N; account for that distortion in the global totals.
const islandGeography:GeographicPoint[]=COASTLINE.map(({x,z})=>[
  ISLAND_ANCHOR.longitude+(x-ISLAND_CENTER.x)/unitsPerRadian/radians,
  ISLAND_ANCHOR.latitude-(z-ISLAND_CENTER.z)/unitsPerRadian/radians,
]);
export const ISLAND_SPHERICAL_AREA_KM2 = sphericalAtlasArea(islandGeography);
export const LAND_AREA_KM2 = LANDMASSES.reduce((area,land)=>area+land.areaKm2,ISLAND_SPHERICAL_AREA_KM2);
export const OCEAN_COVERAGE_PERCENT = 100*(1-LAND_AREA_KM2/PLANET_SURFACE_AREA_KM2);
