package io.buoyant.namerd
package iface

import com.twitter.finagle.{Addr, Address, Dentry, Dtab, Name, Namer, NameTree, Path}
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Closable, Event, Future, Return, Promise, Throw, Try, Var}
import io.buoyant.grpc.runtime.{Stream, EventStream}
import io.buoyant.namer.Metadata
import io.buoyant.proto.{Dtab => ProtoDtab, Path => ProtoPath, _}
import io.buoyant.proto.namerd.{Addr => ProtoAddr, VersionedDtab => ProtoVersionedDtab, _}
import java.net.Inet6Address
import scala.collection.JavaConverters._

/**
 * Utilities for translating between io.buoyant.proto and
 * com.twitter.finagle types.
 *
 * This is necessary because this particular protobuf service models
 * Finagle primitives.
 */
private[iface] object GrpcInterpreterProto {

  val toProtoParseRsp: Try[Dtab] => Future[ParseRsp] = {
    case Return(dtab) =>
      val result = ParseRsp.OneofResult.Dtab(toProtoDtab(dtab))
      Future.value(ParseRsp(Some(result)))

    case Throw(exc) =>
      val e = ParseRsp.Error(Option(exc.getMessage), None)
      val result = ParseRsp.OneofResult.Error(e)
      Future.value(ParseRsp(Some(result)))
  }

  private[this] val WildcardElem =
    ProtoDtab.Dentry.Prefix.Elem(Some(
      ProtoDtab.Dentry.Prefix.Elem.OneofValue.Wildcard(
        ProtoDtab.Dentry.Prefix.Elem.Wildcard()
      )
    ))

  def toProtoPrefix(pfx: Dentry.Prefix): ProtoDtab.Dentry.Prefix =
    ProtoDtab.Dentry.Prefix(pfx.elems.map {
      case Dentry.Prefix.AnyElem => WildcardElem
      case Dentry.Prefix.Label(buf) =>
        ProtoDtab.Dentry.Prefix.Elem(Some(ProtoDtab.Dentry.Prefix.Elem.OneofValue.Label(buf)))
    })

  def fromProtoPrefix(ppfx: ProtoDtab.Dentry.Prefix): Dentry.Prefix =
    Dentry.Prefix(ppfx.elems.map(_prefixElemToProto): _*)

  private[this] val _prefixElemToProto: ProtoDtab.Dentry.Prefix.Elem => Dentry.Prefix.Elem = {
    case WildcardElem => Dentry.Prefix.AnyElem
    case ProtoDtab.Dentry.Prefix.Elem(Some(ProtoDtab.Dentry.Prefix.Elem.OneofValue.Label(buf))) =>
      Dentry.Prefix.Label(buf)
    case elem =>
      throw new IllegalArgumentException(s"Illegal prefix element: $elem")
  }

  def toProtoDtab(dtab: Dtab): ProtoDtab =
    ProtoDtab(dtab.map { dentry =>
      val ppfx = toProtoPrefix(dentry.prefix)
      val pdst = toProtoPathNameTree(dentry.dst)
      ProtoDtab.Dentry(Some(ppfx), Some(pdst))
    })

  def fromProtoDtab(pdtab: ProtoDtab): Dtab =
    Dtab(pdtab.dentries.toIndexedSeq.map {
      case ProtoDtab.Dentry(Some(ppfx), Some(pdst)) =>
        val pfx = fromProtoPrefix(ppfx)
        val dst = fromProtoPathNameTree(pdst)
        Dentry(pfx, dst)
      case dentry =>
        throw new IllegalArgumentException(s"Illegal dentry: $dentry")
    })

  def toProtoPath(path: Path): ProtoPath = ProtoPath(path.elems)
  def fromProtoPath(ppath: ProtoPath): Path = Path(ppath.elems: _*)

  val toProtoPathNameTree: NameTree[Path] => PathNameTree = {
    case NameTree.Neg =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.NEG)))

    case NameTree.Fail =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.FAIL)))

    case NameTree.Empty =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.EMPTY)))

    case NameTree.Leaf(path) =>
      PathNameTree(Some(PathNameTree.OneofNode.Leaf(PathNameTree.Leaf(Some(toProtoPath(path))))))

    case NameTree.Alt(trees@_*) =>
      PathNameTree(Some(PathNameTree.OneofNode.Alt(PathNameTree.Alt(trees.map(toProtoPathNameTree)))))

    case NameTree.Union(trees@_*) =>
      val weighted = trees.map { wt =>
        PathNameTree.Union.Weighted(Some(wt.weight), Some(toProtoPathNameTree(wt.tree)))
      }
      PathNameTree(Some(PathNameTree.OneofNode.Union(PathNameTree.Union(weighted))))
  }

  val fromProtoPathNameTree: PathNameTree => NameTree[Path] = {
    case PathNameTree(Some(PathNameTree.OneofNode.Nop(nop))) =>
      nop match {
        case PathNameTree.Nop.NEG => NameTree.Neg
        case PathNameTree.Nop.FAIL => NameTree.Fail
        case PathNameTree.Nop.EMPTY => NameTree.Empty
      }

    case PathNameTree(Some(PathNameTree.OneofNode.Leaf(PathNameTree.Leaf(Some(path))))) =>
      NameTree.Leaf(fromProtoPath(path))

    case PathNameTree(Some(PathNameTree.OneofNode.Alt(PathNameTree.Alt(ptrees)))) =>
      val trees = ptrees.map(fromProtoPathNameTree)
      NameTree.Alt(trees: _*)

    case PathNameTree(Some(PathNameTree.OneofNode.Union(PathNameTree.Union(ptrees)))) =>
      val trees = ptrees.collect {
        case PathNameTree.Union.Weighted(Some(weight), Some(ptree)) =>
          NameTree.Weighted(weight, fromProtoPathNameTree(ptree))
      }
      NameTree.Union(trees: _*)

    case tree =>
      throw new IllegalArgumentException(s"illegal name tree: $tree")
  }

  def DtabRspError(description: String, code: DtabRsp.Error.Code.Value) = {
    val error = DtabRsp.Error(Some(description), Some(code))
    DtabRsp(Some(DtabRsp.OneofResult.Error(error)))
  }

  val DtabRspNoNamespace =
    DtabRspError("No namespace specified", DtabRsp.Error.Code.BAD_REQUEST)

  val DtabRspNotFound =
    DtabRspError("Namespace not found", DtabRsp.Error.Code.NOT_FOUND)

  val toProtoDtabRsp: VersionedDtab => DtabRsp = { vdtab =>
    val v = ProtoVersionedDtab.Version(Some(vdtab.version))
    val d = toProtoDtab(vdtab.dtab)
    DtabRsp(Some(DtabRsp.OneofResult.Dtab(ProtoVersionedDtab(Some(v), Some(d)))))
  }

  val toProtoDtabRspEv: Try[Option[VersionedDtab]] => EventStream.Ev[DtabRsp] = {
    case Return(None) => EventStream.Val(DtabRspNotFound)
    case Return(Some(vdtab)) => EventStream.Val(toProtoDtabRsp(vdtab))
    case Throw(e) => EventStream.End(Return(DtabRspError(e.getMessage, DtabRsp.Error.Code.UNKNOWN)))
  }

  def BoundTreeRspError(desc: String, code: BoundTreeRsp.Error.Code.Value) = {
    val error = BoundTreeRsp.Error(Some(desc), Some(code))
    BoundTreeRsp(Some(BoundTreeRsp.OneofResult.Error(error)))
  }

  val BoundTreeRspNoNamespace =
    BoundTreeRspError("Namespaces not found", BoundTreeRsp.Error.Code.NOT_FOUND)

  val BoundTreeRspNoName =
    BoundTreeRspError("No name given", BoundTreeRsp.Error.Code.BAD_REQUEST)

  val toProtoBoundNameTree: NameTree[Name.Bound] => BoundNameTree = { tree =>
    val ptree = tree match {
      case NameTree.Neg =>
        BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.NEG)

      case NameTree.Fail =>
        BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.FAIL)

      case NameTree.Empty =>
        BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.EMPTY)

      case NameTree.Leaf(name) =>
        name.id match {
          case id: Path =>
            val leaf = BoundNameTree.Leaf(Some(toProtoPath(id)), Some(toProtoPath(name.path)))
            BoundNameTree.OneofNode.Leaf(leaf)

          case _ =>
            BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.NEG)
        }

      case NameTree.Alt(trees@_*) =>
        BoundNameTree.OneofNode.Alt(BoundNameTree.Alt(trees.map(toProtoBoundNameTree)))

      case NameTree.Union(trees@_*) =>
        BoundNameTree.OneofNode.Union(BoundNameTree.Union(trees.map(toProtoBoundWeightedTree)))
    }
    BoundNameTree(Some(ptree))
  }

  val toProtoBoundWeightedTree: NameTree.Weighted[Name.Bound] => BoundNameTree.Union.Weighted =
    wt => BoundNameTree.Union.Weighted(Some(wt.weight), Some(toProtoBoundNameTree(wt.tree)))

  val toProtoBoundTreeRsp: NameTree[Name.Bound] => BoundTreeRsp =
    t => BoundTreeRsp(Some(BoundTreeRsp.OneofResult.Tree(toProtoBoundNameTree(t))))

  val toProtoBoundTreeRspEv: Try[NameTree[Name.Bound]] => EventStream.Ev[BoundTreeRsp] = {
    case Return(tree) => EventStream.Val(toProtoBoundTreeRsp(tree))
    case Throw(e) => EventStream.End(Throw(e))
  }

  private[this] val _collectEndpoint: PartialFunction[Address, Endpoint] = {
    case Address.Inet(isa, meta) =>
      val port = isa.getPort
      val pmeta = Endpoint.Meta(
        authority = meta.get(Metadata.authority).map(_.toString),
        nodeName = meta.get(Metadata.nodeName).map(_.toString)
      )
      isa.getAddress match {
        case ip: Inet6Address =>
          Endpoint(
            Some(Endpoint.AddressFamily.INET6),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta)
          )
        case ip =>
          Endpoint(
            Some(Endpoint.AddressFamily.INET4),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta)
          )
      }
  }

  def AddrError(msg: String): ProtoAddr =
    ProtoAddr(Some(ProtoAddr.OneofResult.Failed(ProtoAddr.Failed(Some(msg)))))

  val AddrErrorNoId = AddrError("No ID provided")

  val toProtoAddrResult: Addr => ProtoAddr.OneofResult = {
    case Addr.Pending =>
      ProtoAddr.OneofResult.Pending(ProtoAddr.Pending())

    case Addr.Neg =>
      ProtoAddr.OneofResult.Neg(ProtoAddr.Neg())

    case Addr.Failed(exc) =>
      ProtoAddr.OneofResult.Failed(ProtoAddr.Failed(Option(exc.getMessage)))

    case Addr.Bound(addrs, meta) =>
      val pmeta = ProtoAddr.Bound.Meta(authority = meta.get(Metadata.authority).map(_.toString))
      val bound = ProtoAddr.Bound(addrs.collect(_collectEndpoint).toSeq, Some(pmeta))
      ProtoAddr.OneofResult.Bound(bound)
  }

  val toProtoAddr: Addr => ProtoAddr =
    a => ProtoAddr(Some(toProtoAddrResult(a)))

  val toProtoAddrEv: Addr => EventStream.Ev[ProtoAddr] =
    a => EventStream.Val(toProtoAddr(a))
}
