package com.rethinkdb.net;

import com.rethinkdb.ast.Query;
import com.rethinkdb.gen.proto.ResponseType;
import com.rethinkdb.gen.exc.ReqlRuntimeError;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;


public abstract class Cursor<T> implements Iterator<T>, Iterable<T> {

    // public immutable members
    public final long token;

    // immutable members
    protected final Connection connection;
    protected final Query query;

    // mutable members
    protected Deque<T> items = new ArrayDeque<>();
    protected int outstandingRequests = 1;
    protected int threshold = 0;
    protected Optional<RuntimeException> error = Optional.empty();

    public Cursor(Connection connection, Query query) {
        this.connection = connection;
        this.query = query;
        this.token = query.token;
        connection.addToCache(query.token, this);
    }

    public void close() {
        if(!error.isPresent()){
            error = Optional.of(new NoSuchElementException());
            if(connection.isOpen()){
                outstandingRequests += 1;
                connection.stop(this);
            }
        }
    }


    void extend(Response response) {
        outstandingRequests -= 1;
        threshold = response.data.size();
        if(!error.isPresent()){
            if(response.isPartial()){
                items.addAll(response.data);
            } else if(response.isSequence()) {
                items.addAll(response.data);
                error = Optional.of(new NoSuchElementException());
            } else {
                error = Optional.of(response.makeError(query));
            }
        }
        maybeFetchBatch();
        if(outstandingRequests == 0 && error.isPresent()) {
            connection.removeFromCache(response.token);
        }
    }

    protected void maybeFetchBatch() {
        if(!error.isPresent()
                && items.size() <= threshold
                && outstandingRequests == 0) {
            outstandingRequests += 1;
            connection.continue_(this);
        }
    }

    void setError(String errMsg) {
        if(!error.isPresent()){
            error = Optional.of(new ReqlRuntimeError(errMsg));
            Response dummyResponse = Response
                    .make(query.token, ResponseType.SUCCESS_SEQUENCE)
                    .build();
            extend(dummyResponse);
        }
    }

    public static Cursor empty(Connection connection, Query query) {
        return new DefaultCursor(connection, query);
    }

    public T next() {
        return getNext(Optional.empty());
    }

    public T next(int timeout) {
        return getNext(Optional.of(timeout));
    }

    public Iterator<T> iterator(){
        return this;
    }

    // Abstract methods
    abstract T getNext(Optional<Integer> timeout);

    private static class DefaultCursor<T> extends Cursor<T> {
        public final Converter.FormatOptions fmt;
        public DefaultCursor(Connection connection, Query query) {
            super(connection, query);
            fmt = new Converter.FormatOptions(query.globalOptions);
        }

        /* This isn't great, but the Java iterator protocol relies on hasNext,
         so it must be implemented in a reasonable way */
        public boolean hasNext(){
            if(items.size() > 0){
                return true;
            }
            if(error.isPresent()){
                return false;
            }
            maybeFetchBatch();
            connection.readResponse(query.token, Optional.empty());
            return items.size() > 0;
        }

        @SuppressWarnings("unchecked")
        T getNext(Optional<Integer> timeout) {
            while(items.size() == 0) {
                maybeFetchBatch();
                error.ifPresent(exc -> {
                    throw exc;
                });
                connection.readResponse(query.token,
                        timeout.map(Util::deadline));
            }
            Object element = items.pop();
            return (T) Converter.convertPseudotypes(element, fmt);
        }
    }
}