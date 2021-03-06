
/*  $Id: HBCIInstitute.java,v 1.1 2011/05/04 22:37:46 willuhn Exp $

    This file is part of HBCI4Java
    Copyright (C) 2001-2008  Stefan Palme

    HBCI4Java is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    HBCI4Java is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.kapott.hbci.manager;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.spec.KeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;

import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.exceptions.InvalidUserDataException;
import org.kapott.hbci.exceptions.ProcessException;
import org.kapott.hbci.passport.HBCIPassport;
import org.kapott.hbci.passport.HBCIPassportInternal;
import org.kapott.hbci.status.HBCIMsgStatus;

/* @brief Class representing an HBCI institute.

    It it responsible for storing institute-specific-data (the BPD,
    the signature and encryption keys etc.) and for providing
    a Comm object for making communication with the institute */
public final class HBCIInstitute
	implements IHandlerData
{
    private HBCIPassportInternal passport;
    private HBCIKernelImpl       kernel;

    public HBCIInstitute(HBCIKernelImpl kernel,HBCIPassportInternal passport,boolean forceAsParent)
    {
        this.kernel=kernel;
        if (forceAsParent || this.kernel.getParentHandlerData()==null) {
            // Dieser Fall tritt im HBCI4Java-PE ein, wenn ein HBCIInstitute()
            // erzeugt wird, ohne dass es einen HBCIHandler() g�be
            this.kernel.setParentHandlerData(this);
        }
        
        this.passport=passport;
        if (forceAsParent || this.passport.getParentHandlerData()==null) {
            // Dieser Fall tritt im HBCI4Java-PE ein, wenn ein HBCIInstitute()
            // erzeugt wird, ohne dass es einen HBCIHandler() g�be
            this.passport.setParentHandlerData(this);
        }
    }

    /** gets the BPD out of the result and store it in the
        passport field */
    void updateBPD(Properties result)
    {
        HBCIUtils.log("extracting BPD from results",HBCIUtils.LOG_DEBUG);
        Properties p = new Properties();
        
        for (Enumeration e = result.keys(); e.hasMoreElements(); ) {
            String key = (String)(e.nextElement());
            if (key.startsWith("BPD.")) {
                p.setProperty(key.substring(("BPD.").length()), result.getProperty(key));
            }
        }

        if (p.size()!=0) {
            p.setProperty("_hbciversion",kernel.getHBCIVersion());
            passport.setBPD(p);
            HBCIUtils.log("installed new BPD with version "+passport.getBPDVersion(),HBCIUtils.LOG_INFO);
            HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_INST_BPD_INIT_DONE,passport.getBPD());
            
            // send information about successfully received BPD to InfoPoint server
            HBCIUtilsInternal.infoPointSendBPD(passport, result);
        }
    }

    /** gets the server public keys from the result and store them in the passport */
    void extractKeys(Properties result)
    {
        boolean foundChanges=false;
        
        try {
            HBCIUtils.log("extracting public institute keys from results",HBCIUtils.LOG_DEBUG);

            for (int i=0;i<3;i++) {
                String head=HBCIUtilsInternal.withCounter("SendPubKey",i);
                String keyType=result.getProperty(head+".KeyName.keytype");
                if (keyType==null)
                    continue;

                String keyCountry=result.getProperty(head+".KeyName.KIK.country");
                String keyBLZ=result.getProperty(head+".KeyName.KIK.blz");
                String keyUserId=result.getProperty(head+".KeyName.userid");
                String keyNum=result.getProperty(head+".KeyName.keynum");
                String keyVersion=result.getProperty(head+".KeyName.keyversion");

                HBCIUtils.log("found key "+
                        keyCountry+"_"+keyBLZ+"_"+keyUserId+"_"+keyType+"_"+
                        keyNum+"_"+keyVersion,
                        HBCIUtils.LOG_INFO);

                byte[] keyExponent=result.getProperty(head+".PubKey.exponent").getBytes("ISO-8859-1");
                byte[] keyModulus=result.getProperty(head+".PubKey.modulus").getBytes("ISO-8859-1");

                KeyFactory fac=KeyFactory.getInstance("RSA");
                KeySpec spec=new RSAPublicKeySpec(new BigInteger(+1,keyModulus),
                                                  new BigInteger(+1,keyExponent));
                Key key=fac.generatePublic(spec);

                if (keyType.equals("S")) {
                    passport.setInstSigKey(new HBCIKey(keyCountry,keyBLZ,keyUserId,keyNum,keyVersion,key));
                    foundChanges=true;
                } else if (keyType.equals("V")) {
                    passport.setInstEncKey(new HBCIKey(keyCountry,keyBLZ,keyUserId,keyNum,keyVersion,key));
                    foundChanges=true;
                }
            }
        } catch (Exception e) {
            String msg=HBCIUtilsInternal.getLocMsg("EXCMSG_EXTR_IKEYS_ERR");
            if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreExtractKeysErrors",
                                       msg+": "+HBCIUtils.exception2String(e))) {
                throw new HBCI_Exception(msg,e);
            }
        }
        
        if (foundChanges) {
            HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_INST_GET_KEYS_DONE,null);
            acknowledgeNewKeys();
            
            // send information about successfully received keys to InfoPoint server
            HBCIUtilsInternal.infoPointSendPublicKeys(passport, result);
        }
    }
    
    private void acknowledgeNewKeys()
    {
        StringBuffer answer=new StringBuffer();
        HBCIUtilsInternal.getCallback().callback(passport,
                                         HBCICallback.NEED_NEW_INST_KEYS_ACK,
                                         HBCIUtilsInternal.getLocMsg("CALLB_NEW_INST_KEYS"),
                                         HBCICallback.TYPE_BOOLEAN,
                                         answer);

        if (answer.length()>0) {
            try {
                passport.setInstSigKey(null);
                passport.setInstEncKey(null);
                passport.saveChanges();
            } catch (Exception e) {
                HBCIUtils.log(e);
            }
            
            throw new HBCI_Exception(HBCIUtilsInternal.getLocMsg("EXCMSG_KEYSNOTACK"));
        }
    }

    private void doDialogEnd(String dialogid,boolean needSig)
    {
        HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_DIALOG_END,null);
        
        kernel.rawNewMsg("DialogEndAnon");
        kernel.rawSet("MsgHead.dialogid",dialogid);
        kernel.rawSet("MsgHead.msgnum","2");
        kernel.rawSet("DialogEndS.dialogid",dialogid);
        kernel.rawSet("MsgTail.msgnum","2");
        HBCIMsgStatus status=kernel.rawDoIt(HBCIKernelImpl.DONT_SIGNIT,HBCIKernelImpl.DONT_CRYPTIT,needSig,HBCIKernelImpl.DONT_NEED_CRYPT);
        HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_DIALOG_END_DONE,status);
        
        if (!status.isOK()) {
            HBCIUtils.log("dialog end failed: "+status.getErrorString(),HBCIUtils.LOG_ERR);
            
            String msg=HBCIUtilsInternal.getLocMsg("ERR_INST_ENDFAILED");
            if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreDialogEndErrors",
                                       msg+": "+status.getErrorString())) {
                throw new ProcessException(msg,status);
            }
        }
    }

    public void fetchBPD()
    {
        // BPD abholen, wenn nicht vorhanden oder HBCI-Version geaendert
        Properties bpd=passport.getBPD();
        String     hbciVersionOfBPD=(bpd!=null)?bpd.getProperty("_hbciversion"):null;
            
        if (passport.getBPDVersion().equals("0") ||
            hbciVersionOfBPD==null ||
            !hbciVersionOfBPD.equals(kernel.getHBCIVersion())) {
                
            try {
                HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_INST_BPD_INIT,null);
                HBCIUtils.log("fetching BPD",HBCIUtils.LOG_INFO);
                
                HBCIMsgStatus status=null;
                boolean       restarted=false;
                while (true) {
                    kernel.rawNewMsg("DialogInitAnon");
                    kernel.rawSet("Idn.KIK.blz", passport.getBLZ());
                    kernel.rawSet("Idn.KIK.country", passport.getCountry());
                    kernel.rawSet("ProcPrep.BPD", "0");
                    kernel.rawSet("ProcPrep.UPD", passport.getUPDVersion());
                    kernel.rawSet("ProcPrep.lang", "0");
                    kernel.rawSet("ProcPrep.prodName", HBCIUtils.getParam("client.product.name","HBCI4Java"));
                    kernel.rawSet("ProcPrep.prodVersion", HBCIUtils.getParam("client.product.version","2.5"));

                    status=kernel.rawDoIt(HBCIKernelImpl.DONT_SIGNIT,HBCIKernelImpl.DONT_CRYPTIT,
                            HBCIKernelImpl.DONT_NEED_SIG,HBCIKernelImpl.DONT_NEED_CRYPT);

                    boolean need_restart=passport.postInitResponseHook(status, true);
                    if (need_restart) {
                        HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                        if (restarted) {
                            HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                            throw new HBCI_Exception("*** restart loop - aborting");
                        }
                        restarted=true;
                    } else {
                        break;
                    }
                }

                Properties result=status.getData();
                updateBPD(result);
                passport.saveChanges();
                
                try {
                    doDialogEnd(result.getProperty("MsgHead.dialogid"),HBCIKernelImpl.DONT_NEED_SIG);
                } catch (Exception ex) {
                    HBCIUtils.log(ex);
                }
                
                if (!status.isOK()) {
                    HBCIUtils.log("fetching BPD failed: "+status.getErrorString(),HBCIUtils.LOG_ERR);
                    throw new ProcessException(HBCIUtilsInternal.getLocMsg("ERR_INST_BPDFAILED"),status);
                }
            } catch (Exception e) {
                HBCIUtils.log(e);
                HBCIUtils.log("FAILED! - maybe this institute does not support anonymous logins",HBCIUtils.LOG_ERR);
                HBCIUtils.log("we will nevertheless go on",HBCIUtils.LOG_ERR);
            } finally {
                passport.closeComm();
            }
        }

        // ueberpruefen, ob angeforderte sicherheitsmethode auch
        // tatsaechlich unterstuetzt wird
        HBCIUtils.log("checking if requested hbci parameters are supported",HBCIUtils.LOG_DEBUG);
        if (passport.getBPD()!=null) {
            if (!passport.isSupported()) {
                String msg=HBCIUtilsInternal.getLocMsg("EXCMSG_SECMETHNOTSUPP");
                if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreSecMechCheckErrors",msg))
                    throw new InvalidUserDataException(msg);
            }
            
            if (!Arrays.asList(passport.getSuppVersions()).contains(kernel.getHBCIVersion(0))) {
                String msg=HBCIUtilsInternal.getLocMsg("EXCMSG_VERSIONNOTSUPP");
                if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreVersionCheckErrors",msg))
                    throw new InvalidUserDataException(msg);
            }
        } else {
            HBCIUtils.log("can not check if requested parameters are supported",HBCIUtils.LOG_WARN);
        }
    }

    public void fetchKeys()
    {
        // bei RDH institut-keys abholen (wenn nicht vorhanden)
        if (passport.needInstKeys() && !passport.hasInstEncKey()) {
            // TODO: hasInstEncKey(): bei Bankensignatur f�r HKTAN gibt es
            // hier kollisionen, weil hasInstEncKey() f�r PINTAN eigentlich
            // *immer* true zur�ckgibt
            
            try {
                HBCIUtilsInternal.getCallback().status(passport,HBCICallback.STATUS_INST_GET_KEYS,null);
                HBCIUtils.log("fetching institute keys",HBCIUtils.LOG_INFO);
                
                String country=passport.getCountry();
                String blz=passport.getBLZ();
    
                HBCIMsgStatus status=null;
                boolean       restarted=false;
                while (true) {
                    kernel.rawNewMsg("FirstKeyReq");
                    kernel.rawSet("Idn.KIK.blz", blz);
                    kernel.rawSet("Idn.KIK.country", country);
                    kernel.rawSet("KeyReq.SecProfile.method",passport.getProfileMethod());
                    kernel.rawSet("KeyReq.SecProfile.version",passport.getProfileVersion());
                    kernel.rawSet("KeyReq.KeyName.keytype", "V");
                    kernel.rawSet("KeyReq.KeyName.KIK.blz", blz);
                    kernel.rawSet("KeyReq.KeyName.KIK.country", country);
                    kernel.rawSet("KeyReq_2.SecProfile.method",passport.getProfileMethod());
                    kernel.rawSet("KeyReq_2.SecProfile.version",passport.getProfileVersion());
                    kernel.rawSet("KeyReq_2.KeyName.keytype", "S");
                    kernel.rawSet("KeyReq_2.KeyName.KIK.blz", blz);
                    kernel.rawSet("KeyReq_2.KeyName.KIK.country", country);
                    kernel.rawSet("ProcPrep.BPD", passport.getBPDVersion());
                    kernel.rawSet("ProcPrep.UPD", passport.getUPDVersion());
                    kernel.rawSet("ProcPrep.lang", "0");
                    kernel.rawSet("ProcPrep.prodName", HBCIUtils.getParam("client.product.name","HBCI4Java"));
                    kernel.rawSet("ProcPrep.prodVersion", HBCIUtils.getParam("client.product.version","2.5"));

                    status = kernel.rawDoIt(HBCIKernelImpl.DONT_SIGNIT,HBCIKernelImpl.DONT_CRYPTIT,
                            HBCIKernelImpl.DONT_NEED_SIG,HBCIKernelImpl.DONT_NEED_CRYPT);

                    boolean need_restart=passport.postInitResponseHook(status, true);
                    if (need_restart) {
                        HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                        if (restarted) {
                            HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                            throw new HBCI_Exception("*** restart loop - aborting");
                        }
                        restarted=true;
                    } else {
                        break;
                    }
                }

                Properties result=status.getData();
                updateBPD(result);
                extractKeys(result);
                passport.saveChanges();
                
                try {
                    doDialogEnd(result.getProperty("MsgHead.dialogid"),HBCIKernelImpl.DONT_NEED_SIG);
                } catch (Exception ex) {
                    HBCIUtils.log(ex);
                }
                
                if (!status.isOK()) {
                    HBCIUtils.log("fetching institute keys failed: "+status.getErrorString(),HBCIUtils.LOG_ERR);
                    throw new ProcessException(HBCIUtilsInternal.getLocMsg("ERR_INST_GETKEYSFAILED"),status);
                }
            } catch (Exception e) {
                throw new HBCI_Exception(HBCIUtilsInternal.getLocMsg("EXCMSG_FETCH_IKEYS_ERR"),e);
            } finally {
                passport.closeComm();
            }
        }
    }
    
    public void register()
    {
        fetchBPD();
        fetchKeys();
        passport.setPersistentData("_registered_institute", Boolean.TRUE);
    }
    
    public MsgGen getMsgGen()
    {
    	return this.kernel.getMsgGen();
    }
    
    public HBCIPassport getPassport()
    {
    	return this.passport;
    }
}
