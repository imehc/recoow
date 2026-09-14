"""Build the review scene through Blender MCP, without replacing existing scenes.

Run in Blender: ns = runpy.run_path('/absolute/path/build_scene.py')
Then call setup(), build_models(), assemble(), export_assets(), export_native(), render_preview().
Headless iOS resource export from the committed Blender source: make decision-models-ios.
GLB and Blender sources stay here; native USDZ resources are exported straight into the iOS target.
Geometry uses metres, Z up, and the negative Y side as its front in Blender.
glTF/USD exporters convert axes for their respective runtimes.
"""

from pathlib import Path
import argparse
import json
import math
import sys

import bpy
from mathutils import Vector


OUT = Path(__file__).resolve().parent
PROJECT_ROOT = next(path for path in OUT.parents if (path / "ios/recoow.xcodeproj").is_dir())
NATIVE_OUT = PROJECT_ROOT / "ios/recoow/Resources/DecisionModels"
SCENE_NAME = "RecoowDecisionScene"
ASSET_SCENE_NAME = "RecoowDecisionAssets"
TAU = math.tau
MODEL_KEYS = ("Default", "Burger", "Noodles", "Salad", "Sushi", "Sandwich", "Hotpot", "Skewers", "Malatang", "Barbecue", "Luosifen", "DryPotShrimp")
MODEL_ALIASES = {
    "汉堡": "burger", "burger": "burger", "hamburger": "burger",
    "面条": "noodles", "拉面": "noodles", "noodles": "noodles", "ramen": "noodles",
    "轻食": "salad", "沙拉": "salad", "salad": "salad",
    "寿司": "sushi", "sushi": "sushi", "三明治": "sandwich", "sandwich": "sandwich",
    "火锅": "hotpot", "鸳鸯锅": "hotpot", "hotpot": "hotpot", "hot pot": "hotpot",
    "串串": "skewers", "串串香": "skewers", "麻辣烫": "malatang", "malatang": "malatang",
    "烧烤": "barbecue", "烤串": "barbecue", "烧烤串": "barbecue", "bbq": "barbecue", "barbecue": "barbecue",
    "螺蛳粉": "luosifen", "柳州螺蛳粉": "luosifen", "螺狮粉": "luosifen",
    "luosifen": "luosifen", "snail rice noodles": "luosifen",
    "干锅虾": "drypotshrimp", "香辣干锅虾": "drypotshrimp", "干锅香辣虾": "drypotshrimp",
    "dry pot shrimp": "drypotshrimp", "drypot shrimp": "drypotshrimp",
}


def linear(value):
    return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4


def material(key, light, dark=None, roughness=0.78):
    mat = bpy.data.materials.new("RD_" + key)
    mat.use_nodes = True
    rgba = tuple(linear(int(light[i:i + 2], 16) / 255) for i in (0, 2, 4)) + (1,)
    mat.diffuse_color = rgba
    shader = mat.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = rgba
    shader.inputs["Roughness"].default_value = roughness
    mat["lightColor"] = "#" + light
    mat["darkColor"] = "#" + (dark or light)
    return mat


def mat(key):
    return bpy.data.materials["RD_" + key]


def activate(scene):
    bpy.context.window.scene = scene


def empty(name, parent=None):
    obj = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(obj)
    obj.parent = parent
    return obj


def finish(obj, name, key, parent=None, smooth=True):
    obj.name = name
    obj.parent = parent
    obj.data.materials.append(mat(key))
    if smooth and obj.type == "MESH":
        for polygon in obj.data.polygons:
            polygon.use_smooth = True
    return obj


def bevel(obj, amount, segments=2):
    mod = obj.modifiers.new("Soft manufactured edges", "BEVEL")
    mod.width = amount
    mod.segments = segments
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=mod.name)
    return obj


def box(name, xyz, size, key, parent=None, radius=0.03, rotation=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=xyz)
    obj = bpy.context.object
    obj.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if radius:
        bevel(obj, radius, 3)
    if rotation:
        obj.rotation_euler = rotation
    return finish(obj, name, key, parent)


def cylinder(name, xyz, radius, depth, key, parent=None, vertices=48, edge=0.015):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=xyz)
    obj = bpy.context.object
    if edge:
        bevel(obj, edge)
    return finish(obj, name, key, parent)


def draw_button(parent):
    root = empty("DrawButton", parent)
    root["interaction"] = "draw"
    cylinder("ActionBase", (0, 0, 0.333), 0.415, 0.042, "Base", root, vertices=48, edge=0.017)
    cylinder("ActionCap", (0, 0, 0.374), 0.373, 0.061, "Accent", root, vertices=48, edge=0.022)
    return root


def ellipsoid(name, xyz, size, key, parent=None, segments=16, rings=8, rotation=None):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings, radius=1, location=xyz)
    obj = bpy.context.object
    obj.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if rotation:
        obj.rotation_euler = rotation
    return finish(obj, name, key, parent)


def lathe(name, profile, key, parent=None, segments=40):
    vertices = []
    for radius, z in profile:
        vertices.extend((radius * math.cos(i * TAU / segments), radius * math.sin(i * TAU / segments), z) for i in range(segments))
    faces = []
    for ring in range(len(profile) - 1):
        for i in range(segments):
            j = (i + 1) % segments
            faces.append((ring * segments + i, ring * segments + j, (ring + 1) * segments + j, (ring + 1) * segments + i))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return finish(obj, name, key, parent)


def tube(name, points, radius, key, parent=None):
    curve = bpy.data.curves.new(name, "CURVE")
    curve.dimensions = "3D"
    curve.resolution_u = 1
    curve.bevel_depth = radius
    curve.bevel_resolution = 1
    spline = curve.splines.new("POLY")
    spline.points.add(len(points) - 1)
    for point, co in zip(spline.points, points):
        point.co = (*co, 1)
    obj = bpy.data.objects.new(name, curve)
    bpy.context.scene.collection.objects.link(obj)
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.convert(target="MESH")
    return finish(bpy.context.object, name, key, parent)


def prism(name, points, y_front, y_back, key, parent=None, radius=0.01):
    vertices = [(x, y, z) for y in (y_front, y_back) for x, z in points]
    n = len(points)
    faces = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))]
    faces += [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    bpy.context.view_layer.objects.active = obj
    if radius:
        bevel(obj, radius)
    return finish(obj, name, key, parent)


def merge_materials(root):
    """One mesh per material per prefab; clones can share the resulting geometry."""
    groups = {}
    for obj in list(root.children_recursive):
        if obj.type == "MESH":
            groups.setdefault(obj.data.materials[0].name, []).append(obj)
    for name, objects in groups.items():
        bpy.ops.object.select_all(action="DESELECT")
        for obj in objects:
            obj.select_set(True)
        bpy.context.view_layer.objects.active = objects[0]
        if len(objects) > 1:
            bpy.ops.object.join()
        obj = bpy.context.object
        obj.name = root.name + "_" + name.removeprefix("RD_")


def setup():
    if bpy.data.scenes.get(SCENE_NAME) or bpy.data.scenes.get(ASSET_SCENE_NAME):
        raise RuntimeError("Task scenes already exist; inspect them before rebuilding.")
    OUT.mkdir(parents=True, exist_ok=True)
    scene = bpy.data.scenes.new(SCENE_NAME)
    bpy.data.scenes.new(ASSET_SCENE_NAME)
    activate(scene)
    # Shared neutral/accent tokens follow DESGIN.MD; food pigments stay semantic.
    material("Surface", "FFFFFF", "505662")
    material("Base", "D9DEE7", "343943")
    material("Deck", "EDF0F5", "454C59")
    material("Line", "BEC6D3", "242930")
    material("Accent", "3E70E3", "98B3FF", 0.63)
    material("Card", "F7F8FB", "A8B1C2")
    material("Bun", "CFA469", "D4AF7C")
    material("Bread", "EDDBAB", "EAD8B4")
    material("Patty", "695045", "866657")
    material("Leaf", "729565", "90AE7D")
    material("LeafLight", "A1B785", "B1C498")
    material("Tomato", "B56454", "CB8270")
    material("Cheese", "DAB875", "E2C893")
    material("Salmon", "D18B74", "DCA18D")
    material("Rice", "F0EAD7", "E9E3D2")
    material("Nori", "425A4C", "678270")
    material("Wood", "BE9975", "CDAF8D")
    root = empty("DecisionScene")
    root["assetVersion"] = 1
    root["metersPerUnit"] = 1.0
    base = empty("StaticBase", root)
    cylinder("BaseFoot", (0, 0, 0.06), 1.92, 0.12, "Line", base, vertices=80, edge=0.04)
    cylinder("BaseBody", (0, 0, 0.155), 2.12, 0.19, "Base", base, vertices=96, edge=0.055)
    rotor = empty("Turntable", root)
    cylinder("RotatingDeck", (0, 0, 0.268), 2.085, 0.095, "Deck", rotor, vertices=96, edge=0.026)
    draw_button(base)
    pointer = prism("Pointer", [(-0.085, 0.355), (0.085, 0.355), (0, 0.355)], -2.1, -1.94, "Accent", base, radius=0)
    # Replace the degenerate generic prism with a horizontal triangular arrow.
    mesh = pointer.data
    mesh.clear_geometry()
    mesh.from_pydata([(-0.095, -2.10, 0.332), (0.095, -2.10, 0.332), (0, -1.91, 0.332), (-0.095, -2.10, 0.359), (0.095, -2.10, 0.359), (0, -1.91, 0.359)], [], [(0, 2, 1), (3, 4, 5), (0, 1, 4, 3), (1, 2, 5, 4), (2, 0, 3, 5)])
    mesh.update()
    bevel(pointer, 0.012)
    pointer["stationary"] = True
    print("Created two isolated task scenes and the neutral turntable.")


def make_default():
    root = empty("Model_Default")
    root["assetKey"] = "default"
    box("CardFoot", (0, 0, 0.044), (0.50, 0.29, 0.088), "Base", root, 0.035)
    box("CardBody", (0, 0, 0.40), (0.55, 0.145, 0.69), "Card", root, 0.075)
    box("CardFace", (0, -0.078, 0.42), (0.445, 0.016, 0.525), "Surface", root, 0.04)
    # Three quiet dots distinguish an unmatched choice, without baking its title.
    for x in (-0.10, 0, 0.10):
        ellipsoid("OptionMark", (x, -0.092, 0.43), (0.029, 0.009, 0.029), "Accent", root, 12, 6)
    root["labelSurface"] = "front, Y=-0.092; add localized text or the existing photo at runtime"
    merge_materials(root)


def make_burger():
    root = empty("Model_Burger")
    root["assetKey"] = "burger"
    lathe("LowerBun", [(0, 0.01), (0.21, 0.01), (0.29, 0.045), (0.315, 0.095), (0.305, 0.14), (0, 0.14)], "Bun", root)
    cylinder("Patty", (0, 0, 0.165), 0.312, 0.08, "Patty", root, 32, 0.025)
    # Lettuce has a real wavy outline so the silhouette reads at small size.
    vertices = [(0, 0, 0.237)]
    for i in range(32):
        angle = i * TAU / 32
        r = 0.333 + 0.017 * math.cos(i * 2.5)
        vertices.append((r * math.cos(angle), r * math.sin(angle), 0.235 + 0.013 * math.sin(i * 1.7)))
    mesh = bpy.data.meshes.new("LettuceLeaf")
    mesh.from_pydata(vertices, [], [(0, i + 1, (i + 1) % 32 + 1) for i in range(32)])
    mesh.update()
    obj = bpy.data.objects.new("LettuceLeaf", mesh)
    bpy.context.scene.collection.objects.link(obj)
    finish(obj, "LettuceLeaf", "Leaf", root)
    solid = obj.modifiers.new("Leaf thickness", "SOLIDIFY")
    solid.thickness = 0.016
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=solid.name)
    box("Cheese", (0, 0, 0.253), (0.47, 0.47, 0.026), "Cheese", root, 0.012, (0, 0, 0.25))
    cylinder("Tomato", (0, 0, 0.276), 0.286, 0.035, "Tomato", root, 32, 0.012)
    profile = [(0, 0.297), (0.304, 0.297), (0.321, 0.332), (0.313, 0.381), (0.275, 0.44), (0.215, 0.48), (0.13, 0.506), (0, 0.515)]
    lathe("UpperBun", profile, "Bun", root)
    for i in range(11):
        a = i * 2.399963
        r = 0.062 + 0.022 * (i % 6)
        x, y = r * math.cos(a), r * math.sin(a)
        z = 0.516 - 0.48 * r * r
        ellipsoid("Sesame", (x, y, z), (0.024, 0.011, 0.007), "Bread", root, 8, 4, (0, 0, a))
    merge_materials(root)


def bowl(root):
    lathe("CeramicBowl", [(0, 0.016), (0.16, 0.016), (0.185, 0.06), (0.245, 0.13), (0.295, 0.22), (0.328, 0.295), (0.326, 0.32), (0.309, 0.327), (0.293, 0.301), (0.265, 0.232), (0.21, 0.155), (0.15, 0.115), (0, 0.115)], "Surface", root)
    lathe("BowlFoot", [(0, 0), (0.12, 0), (0.14, 0.018), (0.14, 0.035), (0, 0.035)], "Base", root, 32)


def make_noodles():
    root = empty("Model_Noodles")
    root["assetKey"] = "noodles"
    bowl(root)
    cylinder("Broth", (0, 0, 0.277), 0.29, 0.025, "Bun", root, 32, 0.007)
    for row in range(6):
        y = (row - 2.5) * 0.059
        points = []
        for step in range(18):
            x = -0.22 + step / 17 * 0.44
            points.append((x, y + 0.015 * math.sin(step * 0.92 + row), 0.3 + 0.013 * math.cos(step * 0.67 + row)))
        tube("Noodle", points, 0.014, "Bread", root)
    for x, y in [(-0.10, 0.06), (0.12, -0.11), (0.17, 0.09)]:
        box("Scallion", (x, y, 0.321), (0.058, 0.025, 0.018), "Leaf", root, 0.006, (0, 0, 0.7))
    for offset in (-0.041, 0.041):
        box("Chopstick", (0.025, 0.07 + offset, 0.38), (0.78, 0.026, 0.026), "Wood", root, 0.007, (0, -0.08, 0.39))
    merge_materials(root)


def make_salad():
    root = empty("Model_Salad")
    root["assetKey"] = "salad"
    bowl(root)
    for i in range(8):
        a = i * TAU / 8
        ellipsoid("SaladLeaf", (0.16 * math.cos(a), 0.16 * math.sin(a), 0.30 + 0.025 * (i % 2)), (0.135, 0.085, 0.044), "Leaf" if i % 2 else "LeafLight", root, 12, 6, (0.25, 0.25, a))
    ellipsoid("CenterLeaf", (0, 0, 0.32), (0.14, 0.1, 0.05), "Leaf", root, 12, 6)
    for x, y in [(-0.12, -0.10), (0.15, -0.02), (0.025, 0.16)]:
        ellipsoid("Tomato", (x, y, 0.356), (0.065, 0.065, 0.047), "Tomato", root, 12, 6)
    for x, y in [(0.06, -0.12), (-0.12, 0.11), (-0.025, 0.01)]:
        box("Crouton", (x, y, 0.379), (0.062, 0.062, 0.051), "Bread", root, 0.008, (0.1, 0.1, x * 5))
    merge_materials(root)


def make_sushi():
    root = empty("Model_Sushi")
    root["assetKey"] = "sushi"
    box("SushiBoard", (0, 0, 0.032), (0.66, 0.51, 0.064), "Wood", root, 0.05)
    for x in (-0.16, 0.16):
        ellipsoid("Rice", (x, -0.08, 0.135), (0.132, 0.15, 0.075), "Rice", root, 16, 8)
        box("Salmon", (x, -0.08, 0.194), (0.258, 0.27, 0.047), "Salmon", root, 0.031)
        for i in range(3):
            box("SalmonStripe", (x, -0.166 + i * 0.075, 0.219), (0.198, 0.013, 0.006), "Bread", root, 0.003, (0, 0, -0.25))
    roll = cylinder("Roll", (0, 0.145, 0.154), 0.1, 0.175, "Nori", root, 24, 0.012)
    cylinder("RollRice", (0, 0.145, 0.246), 0.081, 0.009, "Rice", root, 24, 0.003)
    cylinder("RollCenter", (0, 0.145, 0.253), 0.04, 0.009, "Salmon", root, 16, 0.002)
    merge_materials(root)


def make_sandwich():
    root = empty("Model_Sandwich")
    root["assetKey"] = "sandwich"
    outer = [(-0.31, 0.025), (0.31, 0.025), (-0.27, 0.51)]
    inner = [(-0.265, 0.061), (0.23, 0.061), (-0.238, 0.449)]
    prism("BreadCrust", outer, -0.205, -0.095, "Bun", root, 0.02)
    prism("BreadFace", inner, -0.21, -0.201, "Bread", root, 0.016)
    prism("BackBread", outer, 0.085, 0.20, "Bun", root, 0.02)
    prism("BackFace", inner, 0.201, 0.21, "Bread", root, 0.016)
    prism("Lettuce", [(-0.323, 0.033), (0.312, 0.033), (-0.275, 0.494)], -0.092, -0.045, "Leaf", root, 0.014)
    prism("Cheese", [(-0.29, 0.039), (0.288, 0.039), (-0.26, 0.476)], -0.045, -0.006, "Cheese", root, 0.01)
    prism("Tomato", inner, -0.006, 0.040, "Tomato", root, 0.012)
    prism("Filling", inner, 0.040, 0.085, "Rice", root, 0.01)
    root.rotation_euler.z = -0.2
    merge_materials(root)


def handle(name, x, z, root):
    points = [(x, -0.12, z), (x * 1.23, -0.12, z), (x * 1.34, -0.07, z), (x * 1.34, 0.07, z), (x * 1.23, 0.12, z), (x, 0.12, z)]
    tube(name, points, 0.027, "Base", root)


def soup_sector(name, start, end, key, root, radius=0.30, z=0.235):
    points = [(0, 0, z)]
    points += [(radius * math.cos(start + (end - start) * i / 24), radius * math.sin(start + (end - start) * i / 24), z) for i in range(25)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(points, [], [(0, i + 1, i + 2) for i in range(24)])
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    finish(obj, name, key, root)


def pot(root, height=0.27):
    lathe("PotBody", [(0, 0.01), (0.23, 0.01), (0.28, 0.055), (0.326, height - 0.035), (0.328, height), (0.304, height + 0.006), (0.29, height - 0.065), (0.19, 0.063), (0, 0.063)], "Base", root)
    lathe("PotRim", [(0.309, height - 0.007), (0.34, height - 0.007), (0.343, height + 0.014), (0.309, height + 0.014)], "Surface", root)
    handle("LeftHandle", -0.318, height - 0.055, root)
    handle("RightHandle", 0.318, height - 0.055, root)


def make_hotpot():
    root = empty("Model_Hotpot")
    root["assetKey"] = "hotpot"
    pot(root)
    soup_sector("SpicySoup", -math.pi / 2, math.pi / 2, "Tomato", root)
    soup_sector("ClearSoup", math.pi / 2, math.pi * 1.5, "Bread", root)
    tube("BrothDivider", [(0, -0.30 + i * 0.60 / 20, 0.257) for i in range(21)], 0.016, "Surface", root)
    for x, y in [(0.14, -0.12), (0.16, 0.10)]:
        tube("Chili", [(x - 0.045, y - 0.035, 0.248), (x, y + 0.006, 0.255), (x + 0.03, y + 0.045, 0.25)], 0.022, "Salmon", root)
    for x, y in [(-0.13, -0.12), (-0.13, 0.11)]:
        ellipsoid("Mushroom", (x, y, 0.252), (0.074, 0.054, 0.029), "Rice", root, 12, 6)
    for x, y in [(-0.15, 0.0), (0.12, 0.0)]:
        box("Scallion", (x, y, 0.263), (0.084, 0.025, 0.016), "Leaf", root, 0.006, (0, 0, 0.5))
    merge_materials(root)


def make_skewers():
    root = empty("Model_Skewers")
    root["assetKey"] = "skewers"
    pot(root, height=0.24)
    cylinder("Soup", (0, 0, 0.211), 0.294, 0.018, "Tomato", root, 32, 0.004)
    for i in range(5):
        x = (i - 2) * 0.105
        y = 0.03 + 0.045 * (i % 2)
        end = Vector((x * 1.37, y + 0.16, 0.72 + 0.025 * (i % 2)))
        start = Vector((x * 0.65, y - 0.03, 0.13))
        tube("BambooSkewer", [start, end], 0.011, "Wood", root)
        for j in range(2):
            p = start.lerp(end, 0.35 + j * 0.20)
            if (i + j) % 2:
                box("SkeweredTofu", p, (0.068, 0.067, 0.063), "Bread", root, 0.012, (0.1, 0.2, 0.3))
            else:
                ellipsoid("SkeweredBall", p, (0.047, 0.047, 0.053), "Patty" if i % 2 else "Leaf", root, 12, 6)
    merge_materials(root)


def make_malatang():
    root = empty("Model_Malatang")
    root["assetKey"] = "malatang"
    bowl(root)
    cylinder("SpicyBroth", (0, 0, 0.277), 0.289, 0.02, "Tomato", root, 32, 0.006)
    for x, y in [(-0.14, -0.1), (-0.17, 0.06), (-0.08, 0.18)]:
        ellipsoid("Greens", (x, y, 0.34), (0.079, 0.10, 0.034), "Leaf", root, 12, 6, (0.3, -0.5, y * 5))
    for x, y in [(0.04, -0.12), (0.18, -0.06), (0.10, 0.08)]:
        ellipsoid("FishBall", (x, y, 0.328), (0.064, 0.061, 0.052), "Rice", root, 12, 6)
    for x, y in [(-0.035, 0.01), (0.015, 0.18)]:
        box("Tofu", (x, y, 0.35), (0.10, 0.077, 0.075), "Bread", root, 0.013, (0.08, 0.0, 0.4))
    for i in range(3):
        points = [(0.13 + 0.03 * i + 0.009 * math.sin(j), 0.065 + j * 0.016, 0.31 + 0.015 * math.cos(j)) for j in range(8)]
        tube("Noodles", points, 0.012, "Bread", root)
    tube("SpoonHandle", [(0.20, 0.16, 0.27), (0.29, 0.25, 0.37), (0.36, 0.34, 0.45)], 0.023, "Base", root)
    ellipsoid("SpoonBowl", (0.18, 0.145, 0.29), (0.05, 0.068, 0.018), "Base", root, 12, 6)
    merge_materials(root)


def make_barbecue():
    root = empty("Model_Barbecue")
    root["assetKey"] = "barbecue"
    box("GrillTray", (0, 0, 0.04), (0.70, 0.54, 0.08), "Base", root, 0.055)
    box("TrayInset", (0, 0, 0.083), (0.62, 0.45, 0.015), "Surface", root, 0.04)
    for i in range(3):
        x = (i - 1) * 0.18
        tube("BambooSkewer", [(x, -0.36, 0.135), (x, 0.32, 0.135)], 0.013, "Wood", root)
        for j in range(3):
            y = -0.12 + j * 0.14
            key = "Patty" if (i + j) % 3 else "Bun"
            box("GrilledMeat", (x, y, 0.152), (0.122, 0.105, 0.092), key, root, 0.022, (0, 0, (j - 1) * 0.12))
            for k in (-1, 1):
                box("GrillMark", (x + k * 0.031, y, 0.20), (0.008, 0.065, 0.005), "Tomato", root, 0.002, (0, 0, -0.2))
        box("Pepper", (x, 0.25, 0.15), (0.13, 0.05, 0.075), "Leaf", root, 0.014)
    merge_materials(root)


def make_luosifen():
    root = empty("Model_Luosifen")
    root["assetKey"] = "luosifen"
    bowl(root)
    cylinder("RedBroth", (0, 0, 0.281), 0.291, 0.021, "Tomato", root, 40, 0.006)
    # Fine rice noodles occupy one side; crisp tofu skin and pickles keep this distinct from ramen.
    for row in range(7):
        y = -0.17 + row * 0.038
        points = [(-0.205 + j * 0.020, y + 0.014 * math.sin(j * 0.85 + row), 0.305 + 0.006 * math.cos(j)) for j in range(12)]
        tube("RiceNoodle", points, 0.010, "Rice", root)
    for i in range(3):
        points = [(0.055 + j * 0.016, 0.02 + i * 0.062, 0.32 + 0.033 * math.sin(j * 1.15)) for j in range(12)]
        tube("CrispTofuSkin", points, 0.022, "Bread", root)
    for i in range(5):
        box("PickledBamboo", (0.08 + (i % 2) * 0.052, -0.13 + (i // 2) * 0.026, 0.313),
            (0.13, 0.022, 0.018), "Cheese", root, 0.005, (0, 0, -0.4 + i * 0.14))
    for i, (x, y) in enumerate([(-0.16, 0.16), (-0.08, 0.21), (-0.21, 0.09)]):
        ellipsoid("WoodEar", (x, y, 0.32), (0.049, 0.038, 0.017), "Patty", root, 12, 6, (0.4, 0.2, i))
    for i in range(6):
        ellipsoid("Peanut", (0.20 + (i % 2) * 0.022, -0.055 + (i // 2) * 0.036, 0.317),
                  (0.024, 0.014, 0.013), "Bun", root, 10, 5, (0, 0, i * 0.8))
    for x, y in [(-0.11, -0.16), (-0.03, -0.20)]:
        ellipsoid("LeafyGreens", (x, y, 0.324), (0.073, 0.043, 0.025), "Leaf", root, 12, 6, (0.2, 0.1, -0.5))
    merge_materials(root)


def make_drypotshrimp():
    if not bpy.data.materials.get("RD_Shrimp"):
        material("Shrimp", "DC9069", "E9AC83")
    root = empty("Model_DryPotShrimp")
    root["assetKey"] = "drypotshrimp"
    lathe("ShallowWok", [(0, 0.025), (0.20, 0.025), (0.26, 0.06), (0.34, 0.14), (0.36, 0.205),
                          (0.34, 0.215), (0.30, 0.16), (0.22, 0.08), (0, 0.08)], "Patty", root)
    lathe("WokRim", [(0.337, 0.193), (0.359, 0.193), (0.361, 0.212), (0.340, 0.220)], "Base", root)
    handle("WokHandleLeft", -0.35, 0.175, root)
    handle("WokHandleRight", 0.35, 0.175, root)
    cylinder("SpicySauce", (0, 0, 0.152), 0.294, 0.012, "Tomato", root, 36, 0.004)
    # Connected C-shaped bodies, tapered heads and fan tails stay recognizable at phone size.
    for i in range(4):
        angle = i * TAU / 4
        cx, cy = 0.175 * math.cos(angle), 0.175 * math.sin(angle)
        rotation = angle - 0.45

        def shrimp_point(x, y, z):
            return (cx + x * math.cos(rotation) - y * math.sin(rotation),
                    cy + x * math.sin(rotation) + y * math.cos(rotation), z)

        arc = [-0.6 * math.pi + step * math.pi * 1.4 / 16 for step in range(17)]
        points = [shrimp_point(0.072 * math.cos(a), 0.072 * math.sin(a), 0.233) for a in arc]
        tube("CurledShrimpBody", points, 0.034, "Shrimp", root)
        head = shrimp_point(0.072 * math.cos(arc[0]), 0.072 * math.sin(arc[0]), 0.239)
        ellipsoid("ShrimpHead", head, (0.051, 0.032, 0.032), "Shrimp", root, 14, 7,
                  (0, 0, arc[0] + rotation + math.pi / 2))
        eye = shrimp_point(0.092 * math.cos(arc[0]), 0.092 * math.sin(arc[0]), 0.264)
        ellipsoid("ShrimpEye", eye, (0.007, 0.007, 0.006), "Nori", root, 8, 4)
        for a in arc[4:14:3]:
            band = [shrimp_point(r * math.cos(a), r * math.sin(a), 0.266) for r in (0.056, 0.072, 0.086)]
            tube("ShrimpShellStripe", band, 0.004, "Rice", root)
        end = arc[-1]
        for fan in (-1, 1):
            center = shrimp_point(0.083 * math.cos(end) + fan * 0.018, 0.092 * math.sin(end) + 0.014, 0.233)
            ellipsoid("ShrimpTail", center, (0.037, 0.021, 0.013), "Shrimp", root, 10, 5, (0, 0, rotation + fan * 0.6))
    for x, y in [(-0.055, -0.03), (0.065, 0.04)]:
        cylinder("LotusSlice", (x, y, 0.275), 0.072, 0.027, "Rice", root, 24, 0.006)
        for i in range(5):
            angle = i * TAU / 5
            cylinder("LotusHole", (x + 0.037 * math.cos(angle), y + 0.037 * math.sin(angle), 0.290),
                     0.011, 0.002, "Patty", root, 10, 0)
    for i in range(3):
        angle = i * TAU / 3 + 0.35
        x, y = 0.27 * math.cos(angle), 0.27 * math.sin(angle)
        tube("DriedChili", [(x - 0.027, y - 0.045, 0.238), (x + 0.008, y, 0.248), (x + 0.02, y + 0.041, 0.24)], 0.015, "Tomato", root)
        box("Scallion", (x * 0.8, y * 0.8, 0.273), (0.073, 0.024, 0.016), "Leaf", root, 0.004, (0, 0, angle))
    merge_materials(root)


MODEL_BUILDERS = {
    "Default": make_default, "Burger": make_burger, "Noodles": make_noodles,
    "Salad": make_salad, "Sushi": make_sushi, "Sandwich": make_sandwich,
    "Hotpot": make_hotpot, "Skewers": make_skewers, "Malatang": make_malatang,
    "Barbecue": make_barbecue, "Luosifen": make_luosifen, "DryPotShrimp": make_drypotshrimp,
}


def extend_models():
    activate(bpy.data.scenes[ASSET_SCENE_NAME])
    for key, builder in MODEL_BUILDERS.items():
        if not bpy.data.objects.get("Model_" + key):
            builder()
    print("Prepared the extended food prefabs, including luosifen and dry-pot shrimp.")


def rebuild_models(keys):
    """Rebuild only explicitly named generated prefabs, leaving other Blender content intact."""
    if not set(keys).issubset(MODEL_BUILDERS):
        raise ValueError("Unknown prefab key")
    scene = bpy.data.scenes[ASSET_SCENE_NAME]
    activate(scene)
    for key in keys:
        root = bpy.data.objects.get("Model_" + key)
        if root:
            if root.name not in scene.objects:
                raise RuntimeError("Refusing to replace a model outside the generated asset scene")
            for child in reversed(list(root.children_recursive)):
                bpy.data.objects.remove(child, do_unlink=True)
            bpy.data.objects.remove(root, do_unlink=True)
        MODEL_BUILDERS[key]()


def build_models():
    activate(bpy.data.scenes[ASSET_SCENE_NAME])
    if bpy.context.scene.objects:
        raise RuntimeError("Asset scene is not empty; inspect before rebuilding.")
    extend_models()
    print(f"Built {len(MODEL_KEYS)} reusable prefabs, including the generic fallback.")


def clone_tree(source, parent, suffix):
    obj = source.copy()
    obj.name = source.name + suffix
    bpy.context.scene.collection.objects.link(obj)
    obj.parent = parent
    for child in source.children:
        clone_tree(child, obj, suffix)
    return obj


def assemble():
    scene = bpy.data.scenes[SCENE_NAME]
    activate(scene)
    rotor = scene.objects["Turntable"]
    populate_scene()
    world = bpy.data.worlds.new("RD_World")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.8, 0.84, 0.92, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.28
    scene.world = world
    # Studio light is neutral and soft; no glow, bloom, HDRI or texture downloads.
    for name, xyz, power, size in [("Key", (-3.5, -4.5, 7), 360, 5.0), ("Fill", (4.0, -1, 5), 110, 5.0), ("Rim", (0, 4, 5.5), 160, 4.0)]:
        data = bpy.data.lights.new("RD_" + name, "AREA")
        data.energy = power
        data.shape = "DISK"
        data.size = size
        obj = bpy.data.objects.new("RD_" + name, data)
        scene.collection.objects.link(obj)
        obj.location = xyz
        obj.rotation_euler = (Vector((0, 0, 0.25)) - obj.location).to_track_quat("-Z", "Y").to_euler()
    data = bpy.data.cameras.new("RD_Camera")
    camera = bpy.data.objects.new("RD_Camera", data)
    scene.collection.objects.link(camera)
    camera.location = (0, -7.8, 6.4)
    camera.rotation_euler = (Vector((0, 0, 0.36)) - camera.location).to_track_quat("-Z", "Y").to_euler()
    data.type = "ORTHO"
    data.ortho_scale = 5.05
    scene.camera = camera
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 48
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1200
    scene.render.resolution_y = 960
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.view_settings.exposure = 0
    scene.view_settings.gamma = 1
    for area in bpy.context.screen.areas:
        if area.type == "VIEW_3D":
            area.spaces.active.region_3d.view_perspective = "CAMERA"
    print("Assembled a review arrangement, with separate reusable stage and prefabs.")


def populate_scene(keys=None):
    """Review arrangement only. Runtime positions are driven by enabled options."""
    scene = bpy.data.scenes[SCENE_NAME]
    activate(scene)
    rotor = scene.objects["Turntable"]
    for obj in list(rotor.children):
        if "optionSlot" in obj:
            for child in reversed(list(obj.children_recursive)):
                bpy.data.objects.remove(child, do_unlink=True)
            bpy.data.objects.remove(obj, do_unlink=True)
    keys = keys if keys is not None else ("Hotpot", "Skewers", "Malatang", "Barbecue", "Luosifen", "DryPotShrimp", "Burger", "Noodles", "Salad", "Sushi", "Sandwich", "Default")
    scale = min(1.0, 8.5 / max(1, len(keys)))
    for i, key in enumerate(keys):
        a = i * TAU / len(keys)
        anchor = empty(f"Choice_{i:02}", rotor)
        anchor.location = (1.50 * math.sin(a), -1.50 * math.cos(a), 0.324)
        anchor.rotation_euler.z = a
        anchor.scale = (scale, scale, scale)
        anchor["optionSlot"] = i
        anchor["assetKey"] = key.lower()
        cylinder(f"ChoicePad_{i:02}", (0, 0, 0.035), 0.46, 0.07, "Surface", anchor, 48, 0.018)
        model = clone_tree(bpy.data.objects["Model_" + key], anchor, f"_Slot{i:02}")
        model.location.z = 0.074
        model["modelRoot"] = True
    print(f"Populated the review scene with {len(keys)} choices.")


def select_tree(root):
    bpy.ops.object.select_all(action="DESELECT")
    root.select_set(True)
    for child in root.children_recursive:
        child.select_set(True)
    bpy.context.view_layer.objects.active = root


def export_glb(root, path):
    select_tree(root)
    bpy.ops.export_scene.gltf(filepath=str(path), export_format="GLB", use_selection=True, use_active_scene=True, export_apply=True, export_texcoords=False, export_normals=True, export_tangents=False, export_animations=False, export_materials="EXPORT", export_extras=True, export_cameras=False, export_lights=False)


def export_assets():
    activate(bpy.data.scenes[ASSET_SCENE_NAME])
    records = []
    for key in MODEL_KEYS:
        root = bpy.data.objects["Model_" + key]
        path = OUT / ("option-" + key.lower() + ".glb")
        export_glb(root, path)
        root.update_tag()
        records.append({"key": key.lower(), "path": path.name, "bytes": path.stat().st_size})
    pad = bpy.data.objects.get("ReusableOptionPad")
    if pad is None:
        pad = cylinder("ReusableOptionPad", (0, 0, 0.035), 0.46, 0.07, "Surface", vertices=48, edge=0.018)
    export_glb(pad, OUT / "option-pad.glb")
    activate(bpy.data.scenes[SCENE_NAME])
    export_glb(bpy.data.objects["DecisionScene"], OUT / "decision-scene.glb")
    # Export a count-independent empty stage, leaving the review arrangement intact.
    select_tree(bpy.data.objects["DecisionScene"])
    for obj in bpy.context.scene.objects:
        if "optionSlot" in obj:
            obj.select_set(False)
            for child in obj.children_recursive:
                child.select_set(False)
    bpy.ops.export_scene.gltf(filepath=str(OUT / "decision-stage.glb"), export_format="GLB", use_selection=True, use_active_scene=True, export_apply=True, export_texcoords=False, export_normals=True, export_animations=False, export_extras=True, export_cameras=False, export_lights=False)
    manifest = {
        "version": 1,
        "source": "Procedurally authored in Blender via MCP; no external assets",
        "coordinateSystem": "glTF +Y up, +Z front; metres",
        "fallback": "default",
        "resolutionOrder": ["explicit available asset key", "normalized exact title alias", "default"],
        "aliases": MODEL_ALIASES,
        "assets": records,
        "materials": {m.name: {"light": m["lightColor"], "dark": m["darkColor"]} for m in bpy.data.materials if m.name.startswith("RD_")},
    }
    (OUT / "asset-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "decision-scene.blend"), copy=True)
    print(json.dumps({"assets": records, "sceneBytes": (OUT / "decision-scene.glb").stat().st_size, "blend": str(OUT / "decision-scene.blend")}, ensure_ascii=False))


def export_native():
    """Export USDZ and its catalog directly into the dedicated iOS resource folder."""
    NATIVE_OUT.mkdir(parents=True, exist_ok=True)
    def save(path):
        bpy.ops.wm.usd_export(filepath=str(path), selected_objects_only=True, export_materials=True, generate_materialx_network=False, convert_orientation=True, export_global_forward_selection="NEGATIVE_Z", export_global_up_selection="Y", export_textures_mode="KEEP")
    activate(bpy.data.scenes[ASSET_SCENE_NAME])
    for key in MODEL_KEYS:
        select_tree(bpy.data.objects["Model_" + key])
        save(NATIVE_OUT / ("option-" + key.lower() + ".usdz"))
    select_tree(bpy.data.objects["ReusableOptionPad"])
    save(NATIVE_OUT / "option-pad.usdz")
    activate(bpy.data.scenes[SCENE_NAME])
    select_tree(bpy.data.objects["DecisionScene"])
    for obj in bpy.context.scene.objects:
        if "optionSlot" in obj:
            obj.select_set(False)
            for child in obj.children_recursive:
                child.select_set(False)
    save(NATIVE_OUT / "decision-stage.usdz")
    manifest_path = OUT / "asset-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["stage"] = {"glb": "decision-stage.glb", "usdz": "decision-stage.usdz"}
    manifest["pad"] = {"glb": "option-pad.glb", "usdz": "option-pad.usdz"}
    for asset in manifest["assets"]:
        asset["usdz"] = "option-" + asset["key"] + ".usdz"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (NATIVE_OUT / "decision-model-catalog.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(MODEL_KEYS)} prefab USDZ files, the empty stage, reusable pad and catalog to {NATIVE_OUT}.")


def render_preview():
    scene = bpy.data.scenes[SCENE_NAME]
    activate(scene)
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = str(OUT / "scene-light.png")
    bpy.ops.render.render(write_still=True)
    print("Rendered " + scene.render.filepath)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export shared decision models into the iOS resource folder.")
    parser.add_argument("command", choices=["export-ios"])
    arguments = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser.parse_args(arguments)
    export_native()
