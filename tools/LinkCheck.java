import java.io.*; import java.lang.classfile.*; import java.lang.classfile.constantpool.*; import java.nio.file.*; import java.util.*; import java.util.zip.*;
/** Checks every net.minecraft member referenced by our classes resolves in the target jars (walking superclasses/interfaces). Usage: LinkCheck LABEL JAR... */
public class LinkCheck {
  /** Package prefixes (internal names) to check; LINK_PREFIXES (comma-separated) adds loader APIs, e.g. net/neoforged/. */
  static final List<String> PREFIXES = new ArrayList<>(List.of("net/minecraft/"));
  static { String p = System.getenv("LINK_PREFIXES"); if (p != null && !p.isBlank()) PREFIXES.addAll(Arrays.asList(p.split(","))); }
  static boolean ours(String n) { for (String p : PREFIXES) if (n.startsWith(p)) return true; return false; }
  static Map<String, ClassModel> target = new HashMap<>();
  static int unreadable; // classes newer than this JDK can parse (e.g. built for Java 27); not checkable
  public static void main(String[] a) throws Exception {
    if (a.length < 2) throw new IllegalArgumentException("usage: LinkCheck LABEL JAR...");
    for (int i = 1; i < a.length; i++) try (ZipFile z = new ZipFile(a[i])) { for (var e : Collections.list(z.entries())) if (e.getName().endsWith(".class")) { try { var cm = ClassFile.of().parse(z.getInputStream(e).readAllBytes()); target.put(cm.thisClass().asInternalName(), cm); } catch (IllegalArgumentException ex) { unreadable++; } } }
    // Guard against checking the wrong jars (e.g. a library) and passing vacuously.
    if (!target.containsKey("net/minecraft/client/Minecraft") || !target.containsKey("net/minecraft/server/MinecraftServer")) throw new IllegalStateException("the jars given lack Minecraft's client or server classes: " + Arrays.toString(Arrays.copyOfRange(a, 1, a.length)));
    int refs = 0, bad = 0; Set<String> seen = new TreeSet<>();
    try (var walk = Files.walk(Path.of("dev"))) { for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
      var cm = ClassFile.of().parse(Files.readAllBytes(p));
      for (PoolEntry pe : cm.constantPool()) {
        if (pe instanceof MemberRefEntry m && ours(m.owner().asInternalName())) {
          String key = m.owner().asInternalName() + "." + m.name().stringValue() + m.type().stringValue();
          if (!seen.add(key)) continue; refs++;
          if (!resolve(m.owner().asInternalName(), m.name().stringValue(), m.type().stringValue(), m instanceof FieldRefEntry)) { bad++; System.out.println("MISSING " + key); }
        } else if (pe instanceof ClassEntry c && ours(c.asInternalName()) && seen.add(c.asInternalName()) && !target.containsKey(c.asInternalName())) { bad++; System.out.println("MISSING class " + c.asInternalName()); }
      } } }
    if (refs == 0) throw new IllegalStateException("no Minecraft references found under dev/; is the mod jar unpacked?");
    System.out.println("Minecraft " + a[0] + ": " + refs + " member refs, " + bad + " missing" + (unreadable > 0 ? " (" + unreadable + " target classes need a newer JDK to read; refs to them are reported missing)" : ""));
  }
  static boolean resolve(String owner, String name, String desc, boolean field) {
    ClassModel c = target.get(owner); if (c == null) return false;
    if (field) { for (var f : c.fields()) if (f.fieldName().equalsString(name) && f.fieldType().equalsString(desc)) return true; }
    else { for (var m : c.methods()) if (m.methodName().equalsString(name) && m.methodType().equalsString(desc)) return true; }
    List<String> sup = new ArrayList<>(); c.superclass().ifPresent(s -> sup.add(s.asInternalName())); c.interfaces().forEach(i -> sup.add(i.asInternalName()));
    for (String s : sup) { if (ours(s) || s.startsWith("com/mojang/")) { if (resolve(s, name, desc, field)) return true; }
      else { try { Class<?> k = Class.forName(s.replace('/', '.')); for (var m : k.getMethods()) if (m.getName().equals(name)) return true; for (var f : k.getFields()) if (f.getName().equals(name)) return true; } catch (Exception e) {} } }
    return false;
  }
}
