package net.cumba.corej.editor.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.editor.config.EditorProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The key a per-client limit counts under — the anonymous runs and searches, the logins (security
 * review M4, L9, L12):
 * <ul>
 * <li>the TCP peer, unless it is one of {@code editor.trusted-proxies}: then the right-most
 * {@code X-Forwarded-For} entry that is not a trusted proxy (the entries left of it are the
 * client's own claims and never believed);</li>
 * <li>an IPv6 client by its {@code /64} — one subscriber's prefix, through which it can rotate
 * addresses at will; an IPv4 client by its address;</li>
 * <li>only IP literals are read ({@code InetAddress.ofLiteral}): a forwarded name never causes a
 * DNS lookup.</li>
 * </ul>
 */
@Component
public final class ClientAddress
{

    static final String FORWARDED_FOR = "X-Forwarded-For";

    /** An address or a CIDR block. */
    static final class Block
    {

        private final byte[] network;

        private final int bits;

        Block(byte[] aNetwork, int aBits)
        {
            network = aNetwork.clone();
            bits = aBits;
        }


        boolean contains(InetAddress aAddress)
        {
            byte[] a = aAddress.getAddress();
            if (a.length != network.length)
            {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++)
            {
                if (a[i] != network[i])
                {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0)
            {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Block> trusted;

    @Autowired
    public ClientAddress(EditorProperties aProperties)
    {
        this(aProperties.getTrustedProxies());
    }


    ClientAddress(List<String> aTrustedProxies)
    {
        List<Block> blocks = new ArrayList<>();
        for (String entry : aTrustedProxies)
        {
            blocks.add(block(entry));
        }
        trusted = Collections.unmodifiableList(blocks);
    }


    /**
     * Parses {@code a.b.c.d}, {@code a.b.c.d/n}, an IPv6 literal or {@code literal/n}.
     *
     * @throws IllegalArgumentException
     *             on anything else — a misconfigured proxy list fails the start
     */
    static Block block(String aEntry)
    {
        String entry = aEntry.strip();
        int slash = entry.indexOf('/');
        InetAddress address = literal(slash < 0 ? entry : entry.substring(0, slash));
        if (address == null)
        {
            throw new IllegalArgumentException(
                    "editor.trusted-proxies: not an IP address or CIDR block: " + aEntry);
        }
        int max = address.getAddress().length * 8;
        int bits = max;
        if (slash >= 0)
        {
            try
            {
                bits = Integer.parseInt(entry.substring(slash + 1));
            }
            catch (NumberFormatException e)
            {
                throw new IllegalArgumentException(
                        "editor.trusted-proxies: not a prefix length: " + aEntry, e);
            }
            if (bits < 0 || bits > max)
            {
                throw new IllegalArgumentException(
                        "editor.trusted-proxies: prefix length out of range: " + aEntry);
            }
        }
        return new Block(address.getAddress(), bits);
    }


    /** An IP literal, never a name lookup; {@code null} when the text is no literal. */
    static @Nullable InetAddress literal(String aText)
    {
        String text = aText.strip();
        if (text.startsWith("[") && text.endsWith("]"))
        {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return InetAddress.ofLiteral(text);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }


    private boolean trusted(InetAddress aAddress)
    {
        for (Block b : trusted)
        {
            if (b.contains(aAddress))
            {
                return true;
            }
        }
        return false;
    }


    /** The limit key of a request. */
    public String key(HttpServletRequest aRequest)
    {
        List<String> forwarded = new ArrayList<>();
        var headers = aRequest.getHeaders(FORWARDED_FOR);
        while (headers != null && headers.hasMoreElements())
        {
            forwarded.add(headers.nextElement());
        }
        return key(aRequest.getRemoteAddr(), forwarded);
    }


    /**
     * The limit key of a peer and its {@code X-Forwarded-For} header values (in arrival order).
     */
    String key(String aPeer, List<String> aForwardedFor)
    {
        InetAddress client = literal(aPeer);
        if (client == null)
        {
            return "peer:" + aPeer.strip().toLowerCase(Locale.ROOT);
        }
        if (trusted(client))
        {
            List<String> hops = new ArrayList<>();
            for (String header : aForwardedFor)
            {
                for (String hop : header.split(",", -1))
                {
                    hops.add(hop);
                }
            }
            for (int i = hops.size() - 1; i >= 0; i--)
            {
                InetAddress hop = literal(hops.get(i));
                if (hop == null)
                {
                    break; // garbage: stop believing the header here
                }
                client = hop;
                if (!trusted(hop))
                {
                    break;
                }
            }
        }
        return bucket(client);
    }


    /** IPv6 by its /64, IPv4 (and an IPv4-mapped address) by the address. */
    static String bucket(InetAddress aAddress)
    {
        if (aAddress instanceof Inet6Address)
        {
            byte[] a = aAddress.getAddress();
            return "ipv6:" + HexFormat.of().formatHex(a, 0, 8) + "::/64";
        }
        return "ipv4:" + aAddress.getHostAddress();
    }
}
