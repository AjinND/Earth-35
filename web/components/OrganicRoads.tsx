"use client";
import {useEffect,useMemo} from "react";
import * as THREE from "three";
import {organicStreets} from "@/lib/organic-layout";
import type {IslandPoint} from "@/lib/island";
import {sidewalkQuads} from "@/lib/street-furniture";
import {useThree} from "@react-three/fiber";
import {roadSurfaceGeometry} from "@/lib/road-geometry";
export default function OrganicRoads({material}:{material:THREE.Material}) {
  const {gl}=useThree();
  const sidewalks=(points:readonly IslandPoint[],offset:number)=>{
    const vertices:number[]=[],indices:number[]=[];
    for(const quad of sidewalkQuads(points,offset)) {
      const n=vertices.length/3;for(const p of quad)vertices.push(p.x,.08,p.z);
      indices.push(n,n+2,n+1,n+1,n+2,n+3);
    }
    const g=new THREE.BufferGeometry();g.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));g.setIndex(indices);g.computeVertexNormals();return g;
  };
  const streets=useMemo(()=>organicStreets.map(points=>({road:roadSurfaceGeometry(points),left:sidewalks(points,-2.4),right:sidewalks(points,2.4)})),[]);
  useEffect(()=>{gl.domElement.dataset.sidewalkJunctions="clipped";},[gl]);
  useEffect(()=>()=>streets.forEach(s=>Object.values(s).forEach(g=>g.dispose())),[streets]);
  return <group name="organic-street-network">
    {streets.map((s,i)=><group key={i}><mesh geometry={s.road} material={material} receiveShadow/>{[s.left,s.right].map((g,j)=><mesh key={j} geometry={g} receiveShadow><meshStandardMaterial color="#b4baa9" roughness={1}/></mesh>)}</group>)}
  </group>;
}
