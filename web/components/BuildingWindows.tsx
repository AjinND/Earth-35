"use client";
import {useEffect,useRef} from "react";
import * as THREE from "three";
export type WindowBox={x:number;y:number;z:number;width:number;height:number;depth:number};
/** All windows of one building share a draw call and the live lighting material. */
export default function BuildingWindows({boxes,material}:{boxes:readonly WindowBox[];material:THREE.Material}) {
  const mesh=useRef<THREE.InstancedMesh>(null);
  useEffect(()=>{
    if(!mesh.current)return;const object=new THREE.Object3D();
    boxes.forEach((b,i)=>{object.position.set(b.x,b.y,b.z);object.scale.set(b.width,b.height,b.depth);object.updateMatrix();mesh.current!.setMatrixAt(i,object.matrix);});
    mesh.current.instanceMatrix.needsUpdate=true;mesh.current.computeBoundingSphere();
  },[boxes]);
  return <instancedMesh ref={mesh} args={[undefined,undefined,boxes.length]} material={material}><boxGeometry args={[1,1,1]}/></instancedMesh>;
}
