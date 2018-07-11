package xyz.zhanhao.dns

import kotlinx.coroutines.experimental.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

const val TOKEN = "{domain}"
const val ZERO = 0.toShort()
const val ONE = 1.toShort()
const val DEFAULT_TTL = 600


data class Query(val name: String, val srcName: ArrayList<String>, val type: Short = ONE, val cls: Short = ZERO)
data class Answer(val namePoint: Short = 0xc00c.toShort(), val type: Short = ONE, val cls: Short = ONE, val ttl: Int = DEFAULT_TTL, val length: Short = 4.toShort(), val data: ByteArray)

data class Header(val id: Short, val flag: Short, val quesCount: Short, val ansCount: Short, val authCount: Short = ZERO, val addiCount: Short = ZERO)
data class DNSReq(val header: Header, val queries: ArrayList<Query>)
data class DNSRsp(val header: Header, val queries: ArrayList<Query>, val answers: ArrayList<Answer>)

fun Header.toByteBuffer(): ByteBuffer {
    val data = ByteBuffer.allocate(12)
    data.putShort(this.id)
    data.putShort(this.flag)
    data.putShort(this.quesCount)
    data.putShort(this.ansCount)
    data.putShort(this.authCount)
    data.putShort(this.addiCount)
    data.flip()
    return data
}

fun Query.toByteBuffer(): ByteBuffer {
    val data = ByteBuffer.allocate(1024)
    this.srcName.forEach {
        data.put(it.length.toByte())
        data.put(it.toByteArray())
    }
    data.put(ZERO.toByte())
    data.putShort(this.type)
    data.putShort(this.cls)
    data.flip()
    return data
}

fun Answer.toByteBuffer(): ByteBuffer {
    val data = ByteBuffer.allocate(1024)
    data.putShort(this.namePoint)
    data.putShort(this.type)
    data.putShort(this.cls)
    data.putInt(this.ttl)
    data.putShort(this.length)
    data.put(this.data)
    data.flip()
    return data
}

fun DNSReq.toByteBuffer(): ByteBuffer {
    val data = ByteBuffer.allocate(1024)
    data.put(this.header.toByteBuffer())
    this.queries.forEach { data.put(it.toByteBuffer()) }
    data.flip()
    return data
}

fun DNSRsp.toByteBuffer(): ByteBuffer {
    val data = ByteBuffer.allocate(1024)
    data.put(this.header.toByteBuffer())
    this.queries.forEach { data.put(it.toByteBuffer()) }
    this.answers.forEach { data.put(it.toByteBuffer()) }
    data.flip()
    return data
}

fun buildReq(data: ByteBuffer): DNSReq {

    val header = Header(data.short, data.short, data.short, data.short, data.short, data.short)
    val queries = ArrayList<Query>(header.quesCount.toInt());

    // queries
    for(i in 1..header.quesCount) {
        var size = data.get()
        val name = ArrayList<String>()
        while(size != ZERO.toByte()) {
            val buf = ByteArray(size.toInt())
            data.get(buf)
            name.add(String(buf))
            size = data.get()
        }
        val type = data.short
        val cls = data.short

        queries.add(Query(name.joinToString("."), name , type, cls))
    }

    return DNSReq(header, queries)
}

fun buildRsp(req: DNSReq, nameAndIps: Map<String, ArrayList<ByteArray>>): DNSRsp {
    val ansCount = nameAndIps.values.sumBy { it.size }
    val header = Header(req.header.id, 0x9c8d.toShort(), req.header.quesCount, ansCount.toShort())
    val answers = ArrayList<Answer>(ansCount)

    var point = 0xc00c
    for(q in req.queries) {
        val ips = nameAndIps[q.name]
        if (ips != null) {
            for(ip in ips) {
                answers.add(Answer(namePoint = point.toShort(), data = ip))
            }
        }

        point += q.toByteBuffer().limit()
    }

    return DNSRsp(header, req.queries, answers)

}



class DnsServer(val port: Int, val reuqestUrl: String, val separator: String) {



    private val channel: DatagramChannel by lazy {
       DatagramChannel.open()
    }

    private val selector: Selector by lazy {
        Selector.open();
    }


    fun start() {
        channel.configureBlocking(false)
        channel.socket().bind(InetSocketAddress(port))
        channel.register(selector, SelectionKey.OP_READ)

        println("DNS start at port:$port...")


        /** 外循环，已经发生了SelectionKey数目 */
        while (selector.select() > 0) {
            /* 得到已经被捕获了的SelectionKey的集合 */
            val iterator = selector.selectedKeys().iterator()
            while (iterator.hasNext()) {
                var key: SelectionKey? = null
                try {
                    key = iterator.next() as SelectionKey
                    iterator.remove()

                    if (key.isReadable) {
                        launch {
                            reveiceAndSend(key)
                        }
                    }

                } catch (e: Throwable) {
                    e.printStackTrace()
                    try {
                        if (key != null) {
                            key.cancel()
                            key.channel().close()
                        }
                    } catch (cex: ClosedChannelException) {
                        e.printStackTrace()
                    }

                }

            }
        }
    }

    private fun reveiceAndSend(key: SelectionKey) {

        val ch = key.channel() as DatagramChannel
        val byteBuf = ByteBuffer.allocate(1024)
        val client = ch.receive(byteBuf)
        byteBuf.flip()

        if(byteBuf.limit() > 0) {
            val req = buildReq(byteBuf)
            println(req)
            val rsp = buildRsp(
                req, getResolve(req.queries)
            )
            println(rsp)
            ch.send(rsp.toByteBuffer(), client) // 将消息回送给客户端
        }
    }

    private fun getResolve(queries: ArrayList<Query>): Map<String, ArrayList<ByteArray>> {
        val nameAndIps = linkedMapOf<String, ArrayList<ByteArray>>()

        queries.forEach {
            val dns = URL(this.reuqestUrl.replace(TOKEN, it.name))
            val yc = dns.openConnection()
            val input = BufferedReader(
                InputStreamReader(
                    yc.getInputStream()
                )
            )
            var result = ""
            var inputLine: String? = input.readLine()
            while (inputLine != null) {
                result += inputLine
                inputLine = input.readLine()
            }
            input.close()
            println(result)

            if(!result.trim().equals("")) {
                nameAndIps.put(it.name, parseAllIP(result));

            }
        }

        return nameAndIps
    }

    private fun parseAllIP(result: String): ArrayList<ByteArray> {
        val ips = result.split(this.separator)
        val list = arrayListOf<ByteArray>()

        ips.forEach {
            val ba = ByteArray(4)
            it.split(".").forEachIndexed { i, s ->
                ba[i] = s.toShort().toByte()
            }
            list.add(ba)
        }

        return list
    }


}

