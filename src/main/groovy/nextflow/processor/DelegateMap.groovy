/*
 * Copyright (c) 2013-2014, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2014, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor
import java.nio.file.Path

import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.util.KryoHelper
/**
 * Map used to delegate variable resolution to script scope
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class DelegateMap implements Map<String,Object> {

    @Delegate
    final private Map<String,Object> holder

    /**
     * The main script owning the process
     */
    private Script script

    /**
     * The process name
     */
    private String name

    /**
     * When {@code true} referencing unknown variable names will true a {@code MissingPropertyException}
     */
    private boolean undef

    /**
     * The name of the variables not hold by the target map, but available in script binding object
     */
    private transient Set<String> bindingNames

    DelegateMap( TaskProcessor processor, Map holder ) {
        this.holder = holder ?: [:]
        this.script = processor.ownerScript
        this.undef = processor.taskConfig.getUndef()
        this.name = processor.name

        // fetch all the variables names referenced by the script body and retain
        // only the ones not declared as input or output, because these are supposed to
        // to be the ones provided by the *external* script context
        bindingNames = processor.getTaskBody().getValNames() ?: []
        if( bindingNames ) {
            Set<String> declaredNames = []
            declaredNames.addAll( processor.taskConfig.getInputs().getNames() )
            declaredNames.addAll( processor.taskConfig.getOutputs().getNames()  )
            if( declaredNames )
                bindingNames = bindingNames - declaredNames
        }

        log.trace "Binding names for '$name' > $bindingNames"
    }


    private DelegateMap(Script script, Map holder, boolean undef, String name) {
        this.script = script
        this.holder = holder
        this.undef = undef
        this.name = name
        def names = script.getBinding()?.getVariables()?.keySet()
        this.bindingNames = names ? new HashSet<>(names) : new HashSet<>()
        log.trace "Binding names for '$name' > $bindingNames"
    }

    /**
     * @return The inner map holding the process variables
     */
    public Map getHolder() {
        return holder
    }

    /**
     * @return The script instance to which this map reference i.e. the main script object
     */
    public Script getScript() {
       script
    }

    @Override
    String toString() {
        "DelegateMap[process: $name; undef: $undef; script: ${script?.class?.name}; holder: ${holder}]"
    }

    @Override
    public Object get(Object property) {

        if( holder.containsKey(property) ) {
            return holder.get(property)
        }

        else if ( script && script.binding?.hasVariable(property?.toString())) {
            return script.binding.getVariable(property.toString())
        }

        if( undef )
        // so give a chance to the bash interpreted to evaluate it
            return '$' + property
        else
            throw new MissingPropertyException("Unknown variable '$property' -- Make sure you didn't misspell it or define somewhere in the script before use it")

    }

    Object invokeMethod(String name, Object args) {
        script.invokeMethod(name, args)
    }

    public getProperty( String name ) {
        get((String)name)
    }

    public void setProperty( String name, def value ) {
        put(name, value)
    }

    @Override
    public put(String property, Object newValue) {
        holder.put(property, newValue)
    }

    /**
     * The the delegate object to the file specified. It takes care to converts {@code Path} objects
     * (that are not serializable) to objects of type {@code SafePath}
     *
     * @param contextFile The file where store the {@code DelegateMap} instance
     */
    def void save( Path contextFile ) {
        try {
            KryoHelper.serialize(holder,contextFile)
        }
        catch( Exception e ) {
            log.warn "Cannot serialize context map. Cause: ${e.cause} -- Resume will not work on this process", e
            log.debug "Failed to serialize delegate map items: ${dumpMap(holder)}"
        }
    }


    @PackageScope
    static String dumpMap( Map map ) {
        def result = []
        result << "[ "
        map.each { key, value -> result << "  '$key':[${value?.class?.name}] = ${value}" }
        result << "]"
        return result.join('\n')
    }

    /**
     * Read the context map from the file specified
     *
     * @param processor The current {@code TaskProcessor}
     * @param contextFile The file used to store the context map
     * @return A new {@code DelegateMap} instance holding the values read from map file
     */
    static DelegateMap read( TaskProcessor processor, Path contextFile ) {

        def map = (Map)KryoHelper.deserialize(contextFile)
        new DelegateMap(processor, map)

    }


    /**
     * Serialize the {@code DelegateMap} instance to a byte array
     */
    def byte[] dehydrate() {
        def kryo = KryoHelper.kryo()
        def buffer = new ByteArrayOutputStream(5*1024)
        def out = new Output(buffer)
        out.writeString(name)
        out.writeBoolean(undef)
        kryo.writeClassAndObject(out,holder)

        // -- the script class
        kryo.writeObject(out, script.class)

        // -- only the binding values for which there's an entry in the holder map
        final copy = new Binding()
        bindingNames.each { it -> checkAndSet(copy, it) }
        log.trace "Delegate for $name > binding copy: ${copy.getVariables()}"
        kryo.writeObject(out, copy)

        out.flush()
        return buffer.toByteArray()
    }

    private void checkAndSet( Binding target, String name ) {
        final binding = this.script.getBinding()
        if( !binding.hasVariable(name) )
            return

        def val = binding.getVariable(name)
        if( val instanceof DataflowReadChannel || val instanceof DataflowWriteChannel )
            return

        if( val instanceof Path || val instanceof Serializable ) {
            target.setVariable(name, val)
        }

    }

    /**
     * Deserialize and create a new instance of the {@code DelegateMap} using the provided byte array serialized binary
     *
     * @param binary
     *          The binary output of a previous {@code #dehydrate} invocation
     * @param loader
     *          An optional class loader to be used to resolve script class when this object
     *          need to be reacted in a remote JVM
     * @return
     *      A {@code DelegateMap} object instantiated using the provided binary byte[]
     */
    static DelegateMap rehydrate(byte[] binary, ClassLoader loader = null) {
        assert binary
        final kryo = KryoHelper.kryo()

        def ClassLoader prev = null
        if( loader ) {
            prev = kryo.getClassLoader()
            kryo.setClassLoader(loader)
        }

        try {
            def input = new Input(new ByteArrayInputStream(binary))
            def name = input.readString()
            def undef = input.readBoolean()
            Map holder = (Map)kryo.readClassAndObject(input)
            Class<Script> clazz = kryo.readObject(input,Class)
            Binding binding = kryo.readObject(input,Binding)

            Script script = clazz.newInstance()
            script.setBinding(binding)
            return new DelegateMap(script, holder, undef, name)
        }
        finally {
            // set back the original class loader
            if( prev ) kryo.setClassLoader(prev)
        }

    }



}