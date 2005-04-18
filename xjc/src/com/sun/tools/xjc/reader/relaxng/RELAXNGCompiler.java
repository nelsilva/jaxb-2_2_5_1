package com.sun.tools.xjc.reader.relaxng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;

import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CBuiltinLeafInfo;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CClassInfoParent;
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.CTypeInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.model.TypeUse;
import com.sun.tools.xjc.model.CEnumConstant;
import com.sun.tools.xjc.model.CNonElement;
import com.sun.xml.bind.v2.NameConverter;

import org.kohsuke.rngom.digested.DChoicePattern;
import org.kohsuke.rngom.digested.DDefine;
import org.kohsuke.rngom.digested.DElementPattern;
import org.kohsuke.rngom.digested.DPattern;
import org.kohsuke.rngom.digested.DValuePattern;
import org.kohsuke.rngom.nc.NameClass;
import org.kohsuke.rngom.xml.util.WellKnownNamespaces;

/**
 * @author Kohsuke Kawaguchi
 */
public final class RELAXNGCompiler {
    /**
     * Schema to compile.
     */
    final DPattern grammar;

    /**
     * All {@link DDefine}s in this schema.
     */
    final Set<DDefine> defs;

    final Options opts;

    final Model model;

    /**
     * The package to which we generate the code into.
     */
    final JPackage pkg;

    final Map<String,DatatypeLib> datatypes = new HashMap<String, DatatypeLib>();

    /**
     * Patterns that are mapped to Java concepts.
     *
     * <p>
     * The value is an array because we often map elements with finite names
     * to multiple classes.
     */
    final Map<DPattern,CTypeInfo[]> classes = new HashMap<DPattern,CTypeInfo[]>();

    /**
     * Classes that need to be bound.
     *
     * The value is the content model to be bound.
     */
    final Map<CClassInfo,DPattern> bindQueue = new HashMap<CClassInfo,DPattern>();

    public static Model build(DPattern grammar, JCodeModel codeModel, Options opts ) {
        RELAXNGCompiler compiler = new RELAXNGCompiler(grammar, codeModel, opts);
        compiler.compile();
        return compiler.model;
    }

    public RELAXNGCompiler(DPattern grammar, JCodeModel codeModel, Options opts) {
        this.grammar = grammar;
        this.opts = opts;
        this.model = new Model(codeModel,NameConverter.smart,opts.classNameAllocator);

        datatypes.put("",DatatypeLib.BUILTIN);
        datatypes.put(WellKnownNamespaces.XML_SCHEMA_DATATYPES,DatatypeLib.XMLSCHEMA);

        // find all defines
        DefineFinder deff = new DefineFinder();
        grammar.accept(deff);
        this.defs = deff.defs;

        if(opts.defaultPackage2!=null)
            pkg = codeModel._package(opts.defaultPackage2);
        else
        if(opts.defaultPackage!=null)
            pkg = codeModel._package(opts.defaultPackage);
        else
            pkg = codeModel.rootPackage();
    }

    private void compile() {
        // decide which patterns to map to classes
        promoteElementDefsToClasse();
        promoteTypeSafeEnums();
        // TODO: promote patterns with <jaxb:class> to classes
        // TODO: promote 'type' patterns to classes

        for (Map.Entry<CClassInfo,DPattern> e : bindQueue.entrySet())
            bindContentModel(e.getKey(),e.getValue());
    }

    private void bindContentModel(CClassInfo clazz, DPattern pattern) {
        // first we decide which patterns in it map to properties
        // then we process each of them by using RawTypeSetBuilder.
        // much like DefaultParticleBinder in XSD

        //RawTypeSetBuilder.build(pattern).addTo();
    }

    private void promoteTypeSafeEnums() {
        // we'll be trying a lot of choices,
        // and most of them will not be type-safe enum.
        // using the same list improves the memory efficiency.
        List<CEnumConstant> members = new ArrayList<CEnumConstant>();

        OUTER:
        for( DDefine def : defs ) {
            DPattern p = def.getPattern();
            if (p instanceof DChoicePattern) {
                DChoicePattern cp = (DChoicePattern) p;

                members.clear();

                // check if the choice consists of all value patterns
                // and that they are of the same datatype
                DValuePattern vp = null;

                for( DPattern child : cp ) {
                    if(child instanceof DValuePattern) {
                        DValuePattern c = (DValuePattern) child;
                        if(vp==null)
                            vp=c;
                        else {
                            if(!vp.getDatatypeLibrary().equals(c.getDatatypeLibrary())
                            || !vp.getType().equals(c.getType()) )
                                continue OUTER; // different type name
                        }

                        members.add(new CEnumConstant(
                            model, model.getNameConverter().toConstantName(c.getValue()),
                            null, c.getValue()
                        ));
                    } else
                        continue OUTER; // not a value
                }

                if(members.isEmpty())
                    continue;   // empty choice

                CNonElement base = CBuiltinLeafInfo.STRING;

                DatatypeLib lib = datatypes.get(vp.getNs());
                if(lib!=null) {
                    TypeUse use = lib.get(vp.getType());
                    if(use instanceof CNonElement)
                        base = (CNonElement)use;
                }

                CEnumLeafInfo xducer = new CEnumLeafInfo(model, null,
                        new CClassInfoParent.Package(pkg), def.getName(), base,
                        new ArrayList<CEnumConstant>(members),
                        null/*TODO*/, cp.getLocation());

                classes.put(cp,new CTypeInfo[]{xducer});
            }
        }
    }


    private void promoteElementDefsToClasse() {
        for( DDefine def : defs ) {
            DPattern p = def.getPattern();
            if (p instanceof DElementPattern) {
                DElementPattern ep = (DElementPattern) p;

                mapToClass(ep);
            }
        }
    }

    private void mapToClass(DElementPattern p) {
        NameClass nc = p.getName();
        if(nc.isOpen())
            return;   // infinite name. can't map to a class.

        Set<QName> names = nc.listNames();

        CClassInfo[] types = new CClassInfo[names.size()];
        int i=0;
        for( QName n : names ) {
            // TODO: read class names from customization
            String name = model.getNameConverter().toClassName(n.getLocalPart());

            bindQueue.put(
                types[i++] = new CClassInfo(model,pkg,name,p.getLocation(),null,n,null/*TODO*/),
                p.getChild() );
        }

        classes.put(p,types);
    }
}
