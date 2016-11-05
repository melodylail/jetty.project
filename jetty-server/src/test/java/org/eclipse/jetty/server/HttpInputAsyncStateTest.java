//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.eclipse.jetty.server.HttpInput.EOF_CONTENT;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.ReadListener;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;


/**
 * this tests HttpInput and its interaction with HttpChannelState
 */


public class HttpInputAsyncStateTest
{

    private static final Queue<String> __history = new LinkedBlockingQueue<>();
    private ByteBuffer _available = BufferUtil.allocate(16*1024);
    private boolean _eof;
    private boolean _noReadInDataAvailable;
    
    private final ReadListener _listener = new ReadListener()
    {
        @Override
        public void onError(Throwable t)
        {
            __history.add("onError:" + t);
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            __history.add("onDataAvailable");
            if (!_noReadInDataAvailable)
                __history.add("read "+readAvailable());
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            __history.add("onAllDataRead");
        }
    };
    private HttpInput _in;
    HttpChannelState _state;

    public static class TContent extends HttpInput.Content
    {
        public TContent(String content)
        {
            super(BufferUtil.toBuffer(content));
        }
    }

    @Before
    public void before()
    {        
        _noReadInDataAvailable = false;
        _in = new HttpInput(new HttpChannelState(new HttpChannel(null, new HttpConfiguration(), null, null)
        {
            @Override
            public void asyncReadFillInterested()
            {
                __history.add("asyncReadFillInterested");
            }
            @Override
            public Scheduler getScheduler()
            {
                return null;
            }
        })
        {
            @Override
            public void onReadUnready()
            {
                super.onReadUnready();
                __history.add("onReadUnready");
            }

            @Override
            public boolean onReadPossible()
            {
                boolean wake = super.onReadPossible();
                __history.add("onReadPossible "+wake);
                return wake;
            }

            @Override
            public boolean onReadReady()
            {
                boolean wake = super.onReadReady();
                __history.add("onReadReady "+wake);
                return wake;
            }
        })
        {
            @Override
            public void wake()
            {
                __history.add("wake");
            }
        };

        _state = _in.getHttpChannelState();
        __history.clear();
    }

    private void check(String... history)
    {
        for (String h:history)
            assertThat(__history.poll(), equalTo(h));
        assertThat(__history.poll(), nullValue());
    }
    
    private void wake()
    {
        handle(null);
    }


    private void handle()
    {
        handle(null);
    }
    
    private void handle(Runnable run)
    {
        Action action = _state.handling();
        loop: while(true)
        {
            switch(action)
            {
                case DISPATCH:
                    if (run==null)
                        Assert.fail();
                    run.run();
                    break;
                    
                case READ_CALLBACK:
                    _in.run();
                    break;
                    
                case WAIT: 
                    break loop;
                    
                default:
                    Assert.fail();
            }
            action = _state.unhandle();
        }
    }
    
    private void deliver(Content... content)
    {
        if (content!=null)
        {
            for (Content c: content)
            {
                if (c==EOF_CONTENT)
                {
                    _in.eof();
                    _eof = true;
                }
                else if (c==HttpInput.EARLY_EOF_CONTENT)
                {
                    _in.earlyEOF();
                    _eof = true;
                }
                else
                {
                    _in.addContent(c);
                    BufferUtil.append(_available,c.getByteBuffer().slice());
                }
            }
        }
    }
    
    int readAvailable()
    {
        int len=0;
        try
        {
            while(_in.isReady())
            {
                int b = _in.read();
                
                if (b<0)
                {
                    System.err.println("read -1");
                    assertTrue(BufferUtil.isEmpty(_available));
                    assertTrue(_eof);
                    break;
                }
                else
                {
                    len++;
                    System.err.println("read "+(char)b);
                    assertFalse(BufferUtil.isEmpty(_available));
                    int a = 0xff & _available.get();
                    assertThat(b,equalTo(a));
                }
            }

            assertTrue(BufferUtil.isEmpty(_available));
        }
        catch(IOException e)
        {
            throw new RuntimeIOException(e);
        }
        
        return len;
    }
    
    
    @After
    public void after()
    {
        Assert.assertThat(__history.poll(), Matchers.nullValue());
    }

    @Test
    public void testInitialEmptyListenInHandle() throws Exception
    {
        deliver(EOF_CONTENT);
        check();      
        
        handle(()->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");       
        });

        check("onAllDataRead");       
    }


    @Test
    public void testInitialEmptyListenAfterHandle() throws Exception
    {
        deliver(EOF_CONTENT);
        
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        _in.setReadListener(_listener);
        check("onReadReady true","wake");
        wake();
        check("onAllDataRead");       
    }

    @Test
    public void testListenInHandleEmpty() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");       
        });

        check("asyncReadFillInterested");
        
        deliver(EOF_CONTENT);
        check("onReadPossible true");
        handle();
        check("onAllDataRead");       
    }


    @Test
    public void testEmptyListenAfterHandle() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        deliver(EOF_CONTENT);
        check();       
        
        _in.setReadListener(_listener);
        check("onReadReady true","wake");
        wake();
        check("onAllDataRead");       
    }


    @Test
    public void testListenAfterHandleEmpty() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        _in.setReadListener(_listener);
        check("asyncReadFillInterested","onReadUnready");

        deliver(EOF_CONTENT);
        check("onReadPossible true");        
        
        handle();
        check("onAllDataRead");       
    }

    

    @Test
    public void testInitialAllContentListenInHandle() throws Exception
    {
        deliver(new TContent("Hello"),EOF_CONTENT);
        check();      
        
        handle(()->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");       
        });

        check("onDataAvailable","read 5","onAllDataRead");       
    }


    @Test
    public void testInitialAllContentListenAfterHandle() throws Exception
    {
        deliver(new TContent("Hello"),EOF_CONTENT);
        
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        _in.setReadListener(_listener);
        check("onReadReady true","wake");
        wake();
        check("onDataAvailable","read 5","onAllDataRead"); 
    }

    
    @Test
    public void testListenInHandleAllContent() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");       
        });

        check("asyncReadFillInterested");
        
        deliver(new TContent("Hello"),EOF_CONTENT);
        check("onReadPossible true","onReadPossible false");
        handle();
        check("onDataAvailable","read 5","onAllDataRead");
    }


    @Test
    public void testAllContentListenAfterHandle() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        deliver(new TContent("Hello"),EOF_CONTENT);
        check();       
        
        _in.setReadListener(_listener);
        check("onReadReady true","wake");
        wake();
        check("onDataAvailable","read 5","onAllDataRead");
    }


    @Test
    public void testListenAfterHandleAllContent() throws Exception
    {
        handle(()->
        {
            _state.startAsync(null);
            check();       
        });

        _in.setReadListener(_listener);
        check("asyncReadFillInterested","onReadUnready");

        deliver(new TContent("Hello"),EOF_CONTENT);
        check("onReadPossible true","onReadPossible false");        
        
        handle();
        check("onDataAvailable","read 5","onAllDataRead");
    }
    
    @Test
    public void testReadAfterOnDataAvailable() throws Exception
    {
        _noReadInDataAvailable = true;
        handle(()->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");       
        });

        check("asyncReadFillInterested");
        
        deliver(new TContent("Hello"),EOF_CONTENT);
        check("onReadPossible true","onReadPossible false");
        
        handle();
        check("onDataAvailable"); 
        
        assertThat(readAvailable(),equalTo(5));
        check("wake"); 
        wake();
        check("onAllDataRead");   
    }
}
